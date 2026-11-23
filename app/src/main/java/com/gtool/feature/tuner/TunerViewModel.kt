package com.gtool.feature.tuner

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gtool.GtoolApplication
import com.gtool.core.audio.AudioCapture
import com.gtool.core.audio.PitchDetector
import com.gtool.core.audio.PitchResult
import com.gtool.core.audio.ToneGenerator
import com.gtool.core.music.Note
import com.gtool.core.music.Tuning
import com.gtool.core.music.TuningLibrary
import com.gtool.core.music.VoicingGenerator
import com.gtool.core.music.centsFrom
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 调音器页面的全部状态。Compose 只读该 StateFlow。
 */
data class TunerUiState(
    val tuning: Tuning = TuningLibrary.STANDARD,
    val customTunings: List<Tuning> = emptyList(),
    val allTunings: List<Tuning> = TuningLibrary.builtIns,
    val a4: Double = 440.0,
    val useFlats: Boolean = false,
    val permissionGranted: Boolean = false,
    val listening: Boolean = false,
    val errorMessage: String? = null,
    /** 平滑后的基频（Hz），null 表示当前没有可信读数。 */
    val frequency: Double? = null,
    /** 检测置信度 0..1。 */
    val clarity: Double = 0.0,
    val detectedNote: Note? = null,
    /** 自动匹配到最近的目标弦下标（0 = 最低音弦）。 */
    val matchedIndex: Int? = null,
    /** 用户手动锁定的弦下标，null 表示自动匹配。 */
    val lockedIndex: Int? = null,
    /** 当前实际参考的目标弦下标（锁定优先，否则为自动匹配）。 */
    val targetIndex: Int? = null,
    /** 相对目标弦的音分偏差，正值偏高。 */
    val cents: Double? = null,
    val inTune: Boolean = false,
    /** 无实时信号时在调音表上方保留的最近一次有效识别读数（切页、切调弦或改 A4 后清空）。 */
    val lastFrequency: Double? = null,
    val lastDetectedNote: Note? = null,
    val lastCents: Double? = null,
    /** 各弦位最近一次识别到的音分偏差（按弦下标缓存，切页或重启后清空）。 */
    val lastStringCents: Map<Int, Double> = emptyMap(),
    val playingIndex: Int? = null
) {
    val targetNote: Note? get() = targetIndex?.let { tuning.strings.getOrNull(it) }
}

/**
 * 调音器 ViewModel：负责麦克风采集、YIN 检测调度、平滑、弦位匹配、调弦/设置持久化、
 * 参考音播放。所有音频回调都在后台线程，只通过 StateFlow 把结果交给 Compose。
 */
class TunerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as GtoolApplication).repository
    private val detector = PitchDetector(sampleRate = AudioCapture.DEFAULT_SAMPLE_RATE.toDouble())

    private val _state = MutableStateFlow(TunerUiState())
    val state: StateFlow<TunerUiState> = _state.asStateFlow()

    @Volatile
    private var listening = false

    private val smoothingLock = Any()
    private val recentFrequencies = ArrayDeque<Double>()
    private var smoothedFrequency: Double? = null
    private var lastAcceptedAt = 0L

    private var capture: AudioCapture? = null
    private var toneGenerator: ToneGenerator? = null

    private var saveA4Job: Job? = null
    private var referenceJob: Job? = null

    private var a4Override: Double? = null
    private var flatsOverride: Boolean? = null
    private var tuningOrderOverride: List<String>? = null
    private var askedForPermission = false

    init {
        observeRepository()
    }

    // ------------------------------------------------------------------ 仓库

    private fun observeRepository() {
        viewModelScope.launch {
            repository.state.collect { appState ->
                val pendingA4 = a4Override
                if (pendingA4 != null && abs(appState.settings.a4 - pendingA4) < 1e-9) {
                    a4Override = null
                }
                val pendingFlats = flatsOverride
                if (pendingFlats != null && appState.settings.useFlats == pendingFlats) {
                    flatsOverride = null
                }
                val pendingOrder = tuningOrderOverride
                if (pendingOrder != null && appState.allTunings.map { it.id } == pendingOrder) {
                    tuningOrderOverride = null
                }
                val resolvedAll = tuningOrderOverride?.let { order ->
                    TuningLibrary.orderedAll(appState.customTunings, order)
                } ?: appState.allTunings
                _state.update { current ->
                    current.copy(
                        tuning = appState.activeTuning,
                        customTunings = resolvedAll.filterNot { it.builtIn },
                        allTunings = resolvedAll,
                        a4 = a4Override ?: appState.settings.a4,
                        useFlats = flatsOverride ?: appState.settings.useFlats
                    )
                }
                refreshDerivedFields()
            }
        }
    }

    private fun refreshDerivedFields() {
        _state.update { current -> withTarget(current, current.lockedIndex) }
    }

    // ------------------------------------------------------------------ 权限

    fun hasRecordPermission(): Boolean = ContextCompat.checkSelfPermission(
        getApplication<Application>(),
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /** 本进程内是否已经自动弹过一次权限请求。 */
    val wasPermissionRequested: Boolean get() = askedForPermission

    fun markPermissionRequested() {
        askedForPermission = true
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permissionGranted = granted) }
        if (granted) startListening() else stopListening()
    }

    // ------------------------------------------------------------------ 采集

    /** 页面可见且已授权时调用；重复调用安全。 */
    fun startListening() {
        if (listening) return
        if (!hasRecordPermission()) {
            _state.update { it.copy(permissionGranted = false) }
            return
        }
        val audioCapture = capture ?: AudioCapture(
            sampleRate = AudioCapture.DEFAULT_SAMPLE_RATE,
            windowSize = AudioCapture.DEFAULT_WINDOW_SIZE,
            hopSize = AudioCapture.DEFAULT_HOP_SIZE,
            onWindow = ::onPitchWindow,
            onError = ::onAudioError
        ).also { capture = it }

        if (!audioCapture.start()) {
            _state.update { current ->
                current.copy(
                    permissionGranted = true,
                    listening = false,
                    errorMessage = current.errorMessage ?: "麦克风启动失败，请检查是否被其他应用占用"
                )
            }
            return
        }
        listening = true
        resetSmoothing()
        _state.update { it.copy(permissionGranted = true, listening = true, errorMessage = null) }
    }

    /** 页面不可见时调用：停止录音与参考音并重置各弦历史音分缓存，保留对象便于快速重启。 */
    fun stopListening() {
        stopReference()
        if (!listening && capture?.isRunning != true) {
            _state.update {
                it.copy(
                    lastFrequency = null,
                    lastDetectedNote = null,
                    lastCents = null,
                    lastStringCents = emptyMap()
                )
            }
            return
        }
        listening = false
        capture?.stop()
        resetSmoothing()
        _state.update { current ->
            current.copy(
                listening = false,
                frequency = null,
                clarity = 0.0,
                detectedNote = null,
                matchedIndex = null,
                targetIndex = current.lockedIndex,
                cents = null,
                inTune = false,
                lastFrequency = null,
                lastDetectedNote = null,
                lastCents = null,
                lastStringCents = emptyMap()
            )
        }
    }

    /** 页面销毁 / ViewModel 清理时调用：释放 AudioRecord 与 AudioTrack。 */
    fun releaseAudio() {
        listening = false
        resetSmoothing()
        capture?.release()
        capture = null
        toneGenerator?.release()
        toneGenerator = null
        referenceJob?.cancel()
        referenceJob = null
        _state.update { current ->
            current.copy(
                listening = false,
                frequency = null,
                clarity = 0.0,
                detectedNote = null,
                matchedIndex = null,
                targetIndex = current.lockedIndex,
                cents = null,
                inTune = false,
                lastFrequency = null,
                lastDetectedNote = null,
                lastCents = null,
                lastStringCents = emptyMap(),
                playingIndex = null
            )
        }
    }

    private fun onAudioError(message: String) {
        listening = false
        resetSmoothing()
        _state.update { current ->
            current.copy(
                listening = false,
                errorMessage = message,
                frequency = null,
                clarity = 0.0,
                detectedNote = null,
                matchedIndex = null,
                targetIndex = current.lockedIndex,
                cents = null,
                inTune = false
            )
        }
        // 不要把阻塞的 stop() 放在音频线程上
        viewModelScope.launch { capture?.stop() }
    }

    // ------------------------------------------------------------------ 检测

    private fun onPitchWindow(samples: FloatArray) {
        if (!listening) return
        val result = detector.detect(samples)
        if (!listening) return
        handlePitchResult(result)
    }

    private fun handlePitchResult(result: PitchResult?) {
        val now = SystemClock.elapsedRealtime()

        if (result == null) {
            val shouldClear = synchronized(smoothingLock) {
                if (smoothedFrequency != null && now - lastAcceptedAt > CLEAR_AFTER_MS) {
                    recentFrequencies.clear()
                    smoothedFrequency = null
                    true
                } else {
                    false
                }
            }
            if (shouldClear) {
                _state.update { current ->
                    current.copy(
                        frequency = null,
                        clarity = 0.0,
                        detectedNote = null,
                        matchedIndex = null,
                        targetIndex = current.lockedIndex,
                        cents = null,
                        inTune = false
                    )
                }
            }
            return
        }

        val smoothed = synchronized(smoothingLock) {
            if (smoothedFrequency != null && now - lastAcceptedAt > RESET_AFTER_MS) {
                recentFrequencies.clear()
                smoothedFrequency = null
            }
            lastAcceptedAt = now

            recentFrequencies.addLast(result.frequency)
            while (recentFrequencies.size > MEDIAN_WINDOW) recentFrequencies.removeFirst()
            val sorted = recentFrequencies.toList().sorted()
            val median = if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }

            val previous = smoothedFrequency
            val next = when {
                previous == null -> median
                // 出现八度/换弦级别的跳变时直接重置，避免指针慢慢滑过去
                abs(centsFrom(median, previous)) > 400.0 -> median
                else -> previous + (median - previous) * EMA_ALPHA
            }
            smoothedFrequency = next
            next
        }

        _state.update { current ->
            if (!listening) return@update current
            val note = Note.fromFrequency(smoothed, current.a4)
            val matched = nearestStringIndex(smoothed, current.tuning, current.a4)
            val targetIndex = current.lockedIndex ?: matched
            val target = targetIndex?.let { current.tuning.strings.getOrNull(it) }
            val cents = target?.let { centsFrom(smoothed, it.frequency(current.a4)) }
            val updatedLastCents = if (targetIndex != null && cents != null) {
                current.lastStringCents + (targetIndex to cents)
            } else {
                current.lastStringCents
            }
            current.copy(
                frequency = smoothed,
                clarity = result.confidence,
                detectedNote = note,
                matchedIndex = matched,
                targetIndex = targetIndex,
                cents = cents,
                inTune = cents != null && abs(cents) <= IN_TUNE_CENTS,
                lastFrequency = smoothed,
                lastDetectedNote = note,
                lastCents = cents ?: current.lastCents,
                lastStringCents = updatedLastCents
            )
        }
    }

    private fun resetSmoothing() {
        synchronized(smoothingLock) {
            recentFrequencies.clear()
            smoothedFrequency = null
            lastAcceptedAt = 0L
        }
    }

    // ------------------------------------------------------------------ 目标弦

    fun toggleStringLock(index: Int) {
        val current = _state.value
        if (index !in current.tuning.strings.indices) return
        val nextLock = if (current.lockedIndex == index) null else index
        _state.update { withTarget(it, nextLock) }
        // 参考音跟随锁定：解锁即停止，播放中改锁其他弦则直接切换到新弦参考音
        val playing = current.playingIndex
        if (playing != null && playing != nextLock) {
            if (nextLock == null) stopReference() else playReference(nextLock)
        }
    }

    fun clearStringLock() {
        _state.update { withTarget(it, null) }
    }

    /**
     * 把检测结果与目标弦一起写回状态；保留真实音分偏差（不跨八度折叠，避免差八度误判为「准」）。
     */
    private fun withTarget(state: TunerUiState, lockedIndex: Int?): TunerUiState {
        val frequency = state.frequency
        val matched = frequency?.let { nearestStringIndex(it, state.tuning, state.a4) }
        val targetIndex = lockedIndex ?: matched
        val target = targetIndex?.let { state.tuning.strings.getOrNull(it) }
        val cents = if (frequency != null && target != null) {
            centsFrom(frequency, target.frequency(state.a4))
        } else {
            null
        }
        return state.copy(
            lockedIndex = lockedIndex,
            matchedIndex = matched,
            targetIndex = targetIndex,
            cents = cents,
            detectedNote = frequency?.let { Note.fromFrequency(it, state.a4) },
            inTune = cents != null && abs(cents) <= IN_TUNE_CENTS
        )
    }

    private fun nearestStringIndex(frequency: Double, tuning: Tuning, a4: Double): Int? {
        if (tuning.strings.isEmpty()) return null
        var bestIndex = 0
        var bestDistance = Double.MAX_VALUE
        tuning.strings.forEachIndexed { index, note ->
            val distance = abs(centsFrom(frequency, note.frequency(a4)))
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    // ------------------------------------------------------------------ 调弦

    fun selectTuning(id: String) {
        val target = _state.value.allTunings.firstOrNull { it.id == id } ?: return
        stopReference()
        _state.update { current ->
            withTarget(
                current.copy(
                    tuning = target,
                    lockedIndex = null,
                    lastFrequency = null,
                    lastDetectedNote = null,
                    lastCents = null,
                    lastStringCents = emptyMap()
                ),
                null
            )
        }
        viewModelScope.launch { repository.setActiveTuning(target.id) }
    }

    fun createCustomTuning(name: String) {
        val base = _state.value.tuning
        stopReference()
        val custom = Tuning(
            id = "custom_" + UUID.randomUUID().toString().take(8),
            name = name.trim().ifEmpty { "自定义调弦" },
            strings = base.strings.toList(),
            builtIn = false
        )
        val nextOrder = _state.value.allTunings.map { it.id } + custom.id
        tuningOrderOverride = nextOrder
        _state.update { current ->
            current.copy(
                tuning = custom,
                customTunings = current.customTunings + custom,
                allTunings = current.allTunings + custom,
                lockedIndex = null,
                lastFrequency = null,
                lastDetectedNote = null,
                lastCents = null,
                lastStringCents = emptyMap()
            ).let { withTarget(it, null) }
        }
        viewModelScope.launch {
            repository.upsertTuning(custom)
            repository.reorderTunings(nextOrder)
            repository.setActiveTuning(custom.id)
        }
    }

    /** 保存自定义调弦（改名 / 逐弦改音）；内置调弦直接忽略。 */
    fun updateCustomTuning(updated: Tuning) {
        if (updated.builtIn) return
        stopReference()
        val clean = updated.copy(
            name = updated.name.trim().ifEmpty { "自定义调弦" },
            builtIn = false
        )
        _state.update { current ->
            val customs = current.customTunings.map { if (it.id == clean.id) clean else it }
            val all = current.allTunings.map { if (it.id == clean.id) clean else it }
            val active = if (current.tuning.id == clean.id) clean else current.tuning
            val lock = current.lockedIndex?.takeIf { it in clean.strings.indices }
            withTarget(
                current.copy(
                    tuning = active,
                    customTunings = customs,
                    allTunings = all,
                    lastFrequency = null,
                    lastDetectedNote = null,
                    lastCents = null,
                    lastStringCents = emptyMap()
                ),
                lock
            )
        }
        VoicingGenerator.clearCache()
        viewModelScope.launch { repository.upsertTuning(clean) }
    }

    fun deleteCustomTuning(tuning: Tuning) {
        if (tuning.builtIn) return
        stopReference()
        val wasActive = _state.value.tuning.id == tuning.id
        val nextOrder = _state.value.allTunings.filterNot { it.id == tuning.id }.map { it.id }
        tuningOrderOverride = nextOrder
        _state.update { current ->
            val customs = current.customTunings.filterNot { it.id == tuning.id }
            val all = current.allTunings.filterNot { it.id == tuning.id }
            val active = if (wasActive) TuningLibrary.STANDARD else current.tuning
            withTarget(
                current.copy(
                    tuning = active,
                    customTunings = customs,
                    allTunings = all,
                    lockedIndex = null,
                    lastFrequency = null,
                    lastDetectedNote = null,
                    lastCents = null,
                    lastStringCents = emptyMap()
                ),
                null
            )
        }
        VoicingGenerator.clearCache()
        viewModelScope.launch {
            if (wasActive) repository.setActiveTuning(TuningLibrary.STANDARD.id)
            repository.deleteTuning(tuning.id)
        }
    }

    /** 保存调弦列表的新排序（内置与自定义统一排序，全局调音器/和弦页同步生效）。 */
    fun reorderTunings(orderedIds: List<String>) {
        val current = _state.value
        val reordered = TuningLibrary.orderedAll(current.customTunings, orderedIds)
        val resolvedIds = reordered.map { it.id }
        if (resolvedIds == current.allTunings.map { it.id }) return
        tuningOrderOverride = resolvedIds
        _state.update { state ->
            state.copy(
                allTunings = reordered,
                customTunings = reordered.filterNot { it.builtIn }
            )
        }
        viewModelScope.launch {
            repository.reorderTunings(resolvedIds)
        }
    }

    // ------------------------------------------------------------------ 设置

    fun setA4(value: Double) {
        val clamped = ((value.coerceIn(MIN_A4, MAX_A4) * 2.0).roundToInt() / 2.0)
        a4Override = clamped
        val playing = _state.value.playingIndex
        _state.update { current ->
            withTarget(
                current.copy(
                    a4 = clamped,
                    lastFrequency = null,
                    lastDetectedNote = null,
                    lastCents = null,
                    lastStringCents = emptyMap()
                ),
                current.lockedIndex
            )
        }
        if (playing != null) {
            val note = _state.value.tuning.strings.getOrNull(playing)
            if (note != null) {
                toneGenerator?.playLoop(frequency = note.frequency(clamped), volume = REFERENCE_VOLUME)
            }
        }
        saveA4Job?.cancel()
        saveA4Job = viewModelScope.launch {
            delay(300)
            repository.updateSettings { it.copy(a4 = clamped) }
        }
    }

    fun setUseFlats(useFlats: Boolean) {
        flatsOverride = useFlats
        _state.update { it.copy(useFlats = useFlats) }
        viewModelScope.launch { repository.updateSettings { it.copy(useFlats = useFlats) } }
    }

    fun toggleFlats() = setUseFlats(!_state.value.useFlats)

    // ------------------------------------------------------------------ 参考音

    /** 循环播放指定弦的参考音；若该弦已在播放则停止。 */
    fun playReference(index: Int) {
        val current = _state.value
        if (current.playingIndex == index) {
            stopReference()
            return
        }
        val note = current.tuning.strings.getOrNull(index) ?: return
        val generator = toneGenerator ?: ToneGenerator(AudioCapture.DEFAULT_SAMPLE_RATE).also {
            toneGenerator = it
        }
        referenceJob?.cancel()
        referenceJob = null
        generator.playLoop(
            frequency = note.frequency(current.a4),
            volume = REFERENCE_VOLUME
        )
        _state.update { it.copy(playingIndex = index) }
    }

    fun stopReference() {
        toneGenerator?.stop()
        referenceJob?.cancel()
        referenceJob = null
        _state.update { it.copy(playingIndex = null) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        releaseAudio()
        super.onCleared()
    }

    companion object {
        const val MIN_A4: Double = 430.0
        const val MAX_A4: Double = 450.0
        const val IN_TUNE_CENTS: Double = 3.0
        const val REFERENCE_DURATION_MS: Int = 2000
        private const val REFERENCE_VOLUME = 0.9f

        private const val MEDIAN_WINDOW = 5
        private const val EMA_ALPHA = 0.35
        private const val CLEAR_AFTER_MS = 400L
        private const val RESET_AFTER_MS = 700L
    }
}

