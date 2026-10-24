package com.gtool.feature.chords

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gtool.GtoolApplication
import com.gtool.core.audio.ChordPlayer
import com.gtool.core.audio.ChordStyle
import com.gtool.core.audio.GuitarSynth
import com.gtool.core.data.AppState
import com.gtool.core.music.ArpeggioPattern
import com.gtool.core.music.Chord
import com.gtool.core.music.ChordQuality
import com.gtool.core.music.PitchClass
import com.gtool.core.music.Tuning
import com.gtool.core.music.TuningLibrary
import com.gtool.core.music.Voicing
import com.gtool.core.music.VoicingGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一次拨弦事件：stringIndex 与 Voicing.frets 下标一致（0 = 最低音弦）。 */
data class PluckEvent(val stringIndex: Int, val timeMs: Long)

data class ChordsUiState(
    val root: PitchClass = PitchClass.C,
    val quality: ChordQuality = ChordQuality.MAJOR,
    val useFlats: Boolean = false,
    val capo: Int = 0,
    val tuning: Tuning = TuningLibrary.STANDARD,
    val allTunings: List<Tuning> = TuningLibrary.builtIns,
    val voicings: List<Voicing> = emptyList(),
    val voicingIndex: Int = 0,
    val style: ChordStyle = ChordStyle.DOWN_STRUM,
    val bpm: Int = 90,
    val loop: Boolean = false,
    val arpeggioPattern: String = ArpeggioPattern.DEFAULT,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false
) {
    val chord: Chord get() = Chord(root, quality)
    val voicing: Voicing? get() = voicings.getOrNull(voicingIndex)
    val chordName: String get() = root.name(useFlats) + quality.symbol
}

class ChordsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as GtoolApplication).repository
    private val synth = GuitarSynth()
    private var player: ChordPlayer? = null

    private val pluckChannel = Channel<PluckEvent>(Channel.BUFFERED)
    val pluckEvents: Flow<PluckEvent> = pluckChannel.receiveAsFlow()

    private val _uiState = MutableStateFlow(ChordsUiState(isLoading = true))
    val uiState: StateFlow<ChordsUiState> = _uiState.asStateFlow()

    private var lastVoicingKey: String? = null
    private var voicingJob: Job? = null
    private var patternPersistJob: Job? = null
    private var patternInitialized = false

    /** 每次停止播放自增；用于丢弃过期的自动续播与 onComplete 回调。 */
    private var playbackEpoch = 0

    init {
        viewModelScope.launch {
            repository.state.collect { appState -> applyRepositoryState(appState) }
        }
        viewModelScope.launch(Dispatchers.Default) {
            synth.warmUp(GuitarSynth.DEFAULT_WARM_UP_RANGE)
        }
    }

    // ------------------------------------------------------------------
    // 选择
    // ------------------------------------------------------------------

    fun setRoot(root: PitchClass) {
        val current = _uiState.value
        if (root == current.root) return
        val wasPlaying = current.isPlaying
        stopPlayback()
        _uiState.value = _uiState.value.copy(root = root)
        maybeRecompute(resetIndex = true, autoPlay = wasPlaying)
    }

    fun setQuality(quality: ChordQuality) {
        val current = _uiState.value
        if (quality == current.quality) return
        val wasPlaying = current.isPlaying
        stopPlayback()
        _uiState.value = _uiState.value.copy(quality = quality)
        maybeRecompute(resetIndex = true, autoPlay = wasPlaying)
    }

    /** 切换变调夹：若正在播放，等新指法生成后自动用新指法继续播放，不打断体验。 */
    fun setCapo(capo: Int) {
        val safe = capo.coerceIn(0, 7)
        val current = _uiState.value
        if (current.capo == safe) return
        val wasPlaying = current.isPlaying
        stopPlayback()
        _uiState.value = _uiState.value.copy(capo = safe)
        maybeRecompute(resetIndex = true, autoPlay = wasPlaying)
        viewModelScope.launch {
            repository.updateSettings { settings -> settings.copy(capo = safe) }
        }
    }

    /** 切换调弦：指板、组成音位置与推荐指法都会按新调弦重算。 */
    fun setTuning(tuningId: String) {
        val current = _uiState.value
        if (current.tuning.id == tuningId) return
        val target = current.allTunings.firstOrNull { it.id == tuningId } ?: return
        val wasPlaying = current.isPlaying
        stopPlayback()
        _uiState.value = _uiState.value.copy(tuning = target)
        maybeRecompute(resetIndex = true, autoPlay = wasPlaying)
        viewModelScope.launch { repository.setActiveTuning(tuningId) }
    }

    /** 自定义分解拨弦顺序；输入时即时生效，350 ms 防抖后持久化。 */
    fun setArpeggioPattern(raw: String) {
        val sanitized = ArpeggioPattern.sanitize(raw)
        val current = _uiState.value
        if (current.arpeggioPattern == sanitized) return
        _uiState.value = current.copy(arpeggioPattern = sanitized)
        patternPersistJob?.cancel()
        patternPersistJob = viewModelScope.launch {
            delay(350)
            val value = ArpeggioPattern.normalize(_uiState.value.arpeggioPattern)
            repository.updateSettings { it.copy(arpeggioPattern = value) }
        }
    }

    fun selectPreviousVoicing() = selectVoicing(_uiState.value.voicingIndex - 1)

    fun selectNextVoicing() = selectVoicing(_uiState.value.voicingIndex + 1)

    private fun selectVoicing(index: Int) {
        val current = _uiState.value
        if (current.voicings.isEmpty()) return
        val safeIndex = index.coerceIn(0, current.voicings.lastIndex)
        if (safeIndex == current.voicingIndex) return
        val wasPlaying = current.isPlaying
        stopPlayback()
        _uiState.value = _uiState.value.copy(voicingIndex = safeIndex)
        prewarm(_uiState.value.voicing)
        if (wasPlaying) startPlayback()
    }

    // ------------------------------------------------------------------
    // 播放设置
    // ------------------------------------------------------------------

    fun setStyle(style: ChordStyle) {
        val current = _uiState.value
        if (current.style == style) return
        val wasPlaying = current.isPlaying
        stopPlayback()
        _uiState.value = _uiState.value.copy(style = style)
        if (wasPlaying) startPlayback()
    }

    fun setLoop(loop: Boolean) {
        val current = _uiState.value
        if (current.loop == loop) return
        val wasPlaying = current.isPlaying
        stopPlayback()
        _uiState.value = _uiState.value.copy(loop = loop)
        if (wasPlaying) startPlayback()
    }

    fun setBpm(bpm: Int) {
        val safe = bpm.coerceIn(60, 120)
        val current = _uiState.value
        if (current.bpm == safe) return
        val wasPlaying = current.isPlaying
        stopPlayback()
        _uiState.value = _uiState.value.copy(bpm = safe)
        if (wasPlaying) startPlayback()
    }

    fun togglePlayback() {
        if (_uiState.value.isPlaying) stopPlayback() else startPlayback()
    }

    fun stopPlayback() {
        playbackEpoch++
        player?.stop()
        drainPluckEvents()
        if (_uiState.value.isPlaying) {
            _uiState.value = _uiState.value.copy(isPlaying = false)
        }
    }

    /** 页面不可见（切换 Tab / 退到后台）时调用：停止播放并释放 AudioTrack。 */
    fun onScreenDisposed() {
        stopPlayback()
        player?.release()
        player = null
    }

    override fun onCleared() {
        player?.release()
        player = null
        synth.clearCache()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun applyRepositoryState(appState: AppState) {
        val current = _uiState.value
        val updated = current.copy(
            useFlats = appState.settings.useFlats,
            capo = appState.settings.capo.coerceIn(0, 7),
            tuning = appState.activeTuning,
            allTunings = appState.allTunings,
            // 只在首次加载时读取持久化的顺序，避免编辑过程中被旧值覆盖
            arpeggioPattern = if (patternInitialized) {
                current.arpeggioPattern
            } else {
                ArpeggioPattern.normalize(appState.settings.arpeggioPattern)
            }
        )
        patternInitialized = true
        if (updated != current) {
            val contextChanged = updated.tuning.id != current.tuning.id || updated.capo != current.capo
            _uiState.value = updated
            // 外部改调弦/变调夹才打断；自己写回设置时 capo 已相同，不会触发
            if (contextChanged) stopPlayback()
        }
        maybeRecompute(resetIndex = true)
    }

    private fun maybeRecompute(resetIndex: Boolean, autoPlay: Boolean = false) {
        val state = _uiState.value
        val key = voicingKey(state)
        if (key == lastVoicingKey && (state.voicings.isNotEmpty() || state.isLoading)) {
            if (autoPlay) startPlayback()
            return
        }
        lastVoicingKey = key
        voicingJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true)
        val epoch = playbackEpoch
        voicingJob = viewModelScope.launch {
            val generated = withContext(Dispatchers.Default) {
                VoicingGenerator.generate(
                    chord = state.chord,
                    tuning = state.tuning,
                    capo = state.capo,
                    maxFret = VoicingGenerator.DEFAULT_MAX_FRET,
                    limit = 5
                )
            }
            val current = _uiState.value
            if (voicingKey(current) != key) return@launch
            val index = if (resetIndex) {
                0
            } else {
                current.voicingIndex.coerceIn(0, (generated.size - 1).coerceAtLeast(0))
            }
            _uiState.value = current.copy(
                voicings = generated,
                voicingIndex = index,
                isLoading = false
            )
            prewarm(generated.firstOrNull())
            // 只有期间没有按过停止（epoch 未变）才自动续播
            if (autoPlay && epoch == playbackEpoch) startPlayback()
        }
    }

    private fun prewarm(voicing: Voicing?) {
        if (voicing == null) return
        val state = _uiState.value
        val midis = voicing.soundedStrings.mapNotNull { index ->
            VoicingGenerator.soundingNote(state.tuning, index, state.capo, voicing.frets[index])?.midi
        }
        viewModelScope.launch(Dispatchers.Default) {
            midis.forEach { synth.pcm(it) }
        }
    }

    private fun startPlayback() {
        val state = _uiState.value
        val voicing = state.voicing ?: return
        if (state.isPlaying) return
        val chordPlayer = ensurePlayer()
        drainPluckEvents()
        _uiState.value = _uiState.value.copy(isPlaying = true)
        val epoch = playbackEpoch
        chordPlayer.play(
            voicing = voicing,
            tuning = state.tuning,
            capo = state.capo,
            style = state.style,
            bpm = state.bpm,
            loop = state.loop,
            arpeggioPattern = ArpeggioPattern.normalize(state.arpeggioPattern),
            onPluck = { stringIndex, timeMs ->
                pluckChannel.trySend(PluckEvent(stringIndex, timeMs))
            },
            onComplete = {
                if (epoch == playbackEpoch) {
                    val current = _uiState.value
                    if (current.isPlaying) {
                        _uiState.value = current.copy(isPlaying = false)
                    }
                }
            }
        )
    }

    private fun ensurePlayer(): ChordPlayer {
        player?.let { return it }
        val application = getApplication<Application>()
        return ChordPlayer(application, synth).also { player = it }
    }

    private fun drainPluckEvents() {
        while (pluckChannel.tryReceive().isSuccess) {
            // 丢弃停止/切换后遗留的事件，避免再次进入页面时出现旧涟漪。
        }
    }

    private fun voicingKey(state: ChordsUiState): String =
        "${state.root.semitone}-${state.quality.id}-${state.tuning.id}-${state.capo}"
}
