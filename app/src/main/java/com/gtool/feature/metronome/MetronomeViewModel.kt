package com.gtool.feature.metronome

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gtool.GtoolApplication
import com.gtool.core.audio.BeatEvent
import com.gtool.core.audio.ClickParams
import com.gtool.core.audio.ClickPlayer
import com.gtool.core.data.MetronomeSettings
import com.gtool.core.device.TorchCapabilities
import com.gtool.core.device.TorchController
import com.gtool.core.device.VibratorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 四种可组合的输出模式。 */
enum class MetronomeMode { SOUND, SCREEN, TORCH, VIBRATE }

/**
 * 节拍器页面状态与调度。
 *
 * 时间基准完全来自 [ClickPlayer] 的 [BeatEvent.nanoTime]：
 * - 画面由 Compose 帧回调按时间戳激活（见 FlashOverlay.kt）；
 * - 闪光灯/振动在这里按时间戳延迟触发，不用 BPM 自己计时。
 */
class MetronomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val MIN_BPM = ClickPlayer.MIN_BPM
        const val MAX_BPM = ClickPlayer.MAX_BPM

        private const val TAP_TIMEOUT_NANOS = 2_500_000_000L
        private const val KEY_VOLUME = "volume"
        private const val KEY_STRONG_FLASH = "strong_flash_ack"
        private const val KEY_SAFETY_HINT = "safety_hint_shown"
    }

    private val repository = (application as GtoolApplication).repository
    private val player = ClickPlayer(application)
    private val torch = TorchController(application)
    private val vibrator = VibratorController(application)
    private val prefs = application.getSharedPreferences("metronome_ui", Context.MODE_PRIVATE)
    private val torchProbeMutex = Mutex()

    /** 持久化设置（含节拍器参数）。DataStore 首次读取前使用默认值。 */
    val settings: StateFlow<MetronomeSettings> = repository.state
        .map { it.settings.metronome }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MetronomeSettings())

    private val _bpm = MutableStateFlow(MetronomeSettings().bpm)

    /** 当前 BPM（拖动过程中是本地实时值，松手后才落盘）。 */
    val bpm: StateFlow<Int> = _bpm.asStateFlow()

    private var bpmDirty = false

    val isPlaying: StateFlow<Boolean> = player.isPlaying

    /** 拍点事件，UI 按 nanoTime 驱动动画。 */
    val beatEvents: SharedFlow<BeatEvent> = player.events

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 一次性提示/错误信息。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _torchState = MutableStateFlow<TorchCapabilities?>(null)

    /** 闪光灯能力；null 表示尚未探测完成。 */
    val torchState: StateFlow<TorchCapabilities?> = _torchState.asStateFlow()

    private val _strongFlashAck = MutableStateFlow(prefs.getBoolean(KEY_STRONG_FLASH, false))

    /** 用户是否已确认“高频强闪”风险（跨启动保留）。 */
    val strongFlashAck: StateFlow<Boolean> = _strongFlashAck.asStateFlow()

    private val _safetyHintShown = MutableStateFlow(prefs.getBoolean(KEY_SAFETY_HINT, false))

    /** 是否已经看过光敏安全提示（只弹一次）。 */
    val safetyHintShown: StateFlow<Boolean> = _safetyHintShown.asStateFlow()

    private val _volume = MutableStateFlow(prefs.getFloat(KEY_VOLUME, 0.9f))

    /** click 音量 0..1。MetronomeSettings 没有该字段，单独存在本模块的 SharedPreferences。 */
    val volume: StateFlow<Float> = _volume.asStateFlow()

    // 滑杆拖动中的“预览值”：实时作用于画面/闪光，松手才写入 DataStore。
    private val _previewBrightness = MutableStateFlow<Float?>(null)
    val previewBrightness: StateFlow<Float?> = _previewBrightness.asStateFlow()

    private val _previewColor = MutableStateFlow<Long?>(null)
    val previewColor: StateFlow<Long?> = _previewColor.asStateFlow()

    private val _torchStrengthPreview = MutableStateFlow<Int?>(null)

    /** 闪光灯强度拖动预览（不落盘）。 */
    val torchStrengthPreview: StateFlow<Int?> = _torchStrengthPreview.asStateFlow()

    val vibratorSupported: Boolean = vibrator.isSupported

    private val tapTimes = ArrayDeque<Long>()
    private var dispatchJob: Job? = null

    init {
        player.setVolume(_volume.value)

        viewModelScope.launch {
            settings.collect { current ->
                if (!bpmDirty && _bpm.value != current.bpm) {
                    _bpm.value = current.bpm.coerceIn(MIN_BPM, MAX_BPM)
                }
                player.update(buildParams())
            }
        }

        viewModelScope.launch {
            player.isPlaying.collect { playing ->
                if (playing) {
                    // 例如音频焦点恢复后自动续播：重新挂上闪光灯/振动调度。
                    if (dispatchJob == null) startBeatDispatch()
                } else {
                    stopBeatDispatch()
                }
            }
        }

        viewModelScope.launch {
            player.messages.collect { message -> _messages.emit(message) }
        }

        viewModelScope.launch {
            torch.errors.collect { message ->
                _messages.emit(message)
                if (settings.value.torchEnabled) {
                    updateMetronome { it.copy(torchEnabled = false) }
                }
            }
        }

        viewModelScope.launch { initializeTorch() }
    }

    // ------------------------------------------------------------ 播放控制

    fun togglePlay() {
        if (isPlaying.value) pause() else play()
    }

    fun play() {
        if (isPlaying.value) return
        if (bpmDirty) {
            bpmDirty = false
            persistBpm(_bpm.value)
        }
        startBeatDispatch()
        player.start(buildParams())
    }

    fun pause() {
        player.pause()
        stopBeatDispatch()
    }

    fun stop() {
        player.stop()
        stopBeatDispatch()
    }

    // ------------------------------------------------------------ BPM / Tap

    /**
     * 设置 BPM。[commit] 为 false 时只实时生效不落盘（拖动中），
     * 为 true 时写入 DataStore（松手/按键/输入框）。
     */
    fun setBpm(value: Int, commit: Boolean = false) {
        val next = value.coerceIn(MIN_BPM, MAX_BPM)
        bpmDirty = true
        _bpm.value = next
        player.update(buildParams())
        if (commit) {
            bpmDirty = false
            persistBpm(next)
        }
    }

    fun nudgeBpm(delta: Int) {
        setBpm(_bpm.value + delta, commit = true)
    }

    /** Tap Tempo：保留最近 4 次点击，取平均间隔换算 BPM。 */
    fun tapTempo() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (tapTimes.isNotEmpty() && now - tapTimes.last() > TAP_TIMEOUT_NANOS) {
            tapTimes.clear()
        }
        tapTimes.addLast(now)
        while (tapTimes.size > 4) tapTimes.removeFirst()
        if (tapTimes.size < 2) {
            _messages.tryEmit("再连续点 3 下即可测速")
            return
        }
        var total = 0L
        for (index in 1 until tapTimes.size) {
            total += tapTimes.elementAt(index) - tapTimes.elementAt(index - 1)
        }
        val average = total.toDouble() / (tapTimes.size - 1)
        if (average <= 0.0) return
        val next = (60_000_000_000.0 / average).roundToInt().coerceIn(MIN_BPM, MAX_BPM)
        bpmDirty = false
        _bpm.value = next
        player.update(buildParams())
        persistBpm(next)
        _messages.tryEmit("Tap Tempo：$next BPM")
    }

    private fun persistBpm(value: Int) {
        updateMetronome { it.copy(bpm = value.coerceIn(MIN_BPM, MAX_BPM)) }
    }

    // ------------------------------------------------------------ 拍号 / 细分 / 重音

    fun setTimeSignature(beatsPerBar: Int, beatUnit: Int) {
        val beats = beatsPerBar.coerceIn(1, 12)
        val unit = if (beatUnit in 1..16) beatUnit else 4
        updateMetronome { current ->
            val pattern = List(beats) { index ->
                val fallback = if (index == 0) 2 else 1
                current.accentPattern.getOrElse(index) { fallback }.coerceIn(0, 2)
            }
            current.copy(beatsPerBar = beats, beatUnit = unit, accentPattern = pattern)
        }
    }

    fun setSubdivision(value: Int) {
        updateMetronome { it.copy(subdivision = value.coerceIn(1, 4)) }
    }

    /** 点一下拍点：重音(2) → 普通(1) → 静音(0) → 重音。 */
    fun cycleAccent(beatIndex: Int) {
        updateMetronome { current ->
            val beats = current.beatsPerBar.coerceAtLeast(1)
            val index = Math.floorMod(beatIndex, beats)
            val pattern = List(beats) { i ->
                val fallback = if (i == 0) 2 else 1
                current.accentPattern.getOrElse(i) { fallback }.coerceIn(0, 2)
            }.toMutableList()
            pattern[index] = when (pattern[index]) {
                2 -> 1
                1 -> 0
                else -> 2
            }
            current.copy(accentPattern = pattern)
        }
    }

    // ------------------------------------------------------------ 模式开关

    fun setMode(mode: MetronomeMode, enabled: Boolean) {
        when (mode) {
            MetronomeMode.SOUND -> updateMetronome { current ->
                var next = current.copy(soundEnabled = enabled)
                val nothingElse = !current.screenEnabled && !current.torchEnabled && !current.vibrateEnabled
                if (!enabled && nothingElse) {
                    // 关掉声音后如果没有任何静音输出，自动打开屏幕节拍。
                    next = next.copy(screenEnabled = true)
                }
                next
            }

            MetronomeMode.SCREEN -> updateMetronome { it.copy(screenEnabled = enabled) }
            MetronomeMode.TORCH -> setTorchEnabled(enabled)
            MetronomeMode.VIBRATE -> setVibrateEnabled(enabled)
        }
    }

    private fun setTorchEnabled(enabled: Boolean) {
        if (!enabled) {
            torch.turnOff()
            updateMetronome { it.copy(torchEnabled = false) }
            return
        }
        viewModelScope.launch {
            val capabilities = ensureTorchProbed()
            if (!capabilities.available) {
                _messages.emit(capabilities.error ?: "本机没有可用的闪光灯")
                return@launch
            }
            if (!capabilities.supportsStrengthControl) {
                _messages.emit("本机不支持闪光灯亮度调节，已使用开/关模式")
            }
            updateMetronome { it.copy(torchEnabled = true) }
        }
    }

    private fun setVibrateEnabled(enabled: Boolean) {
        if (enabled && !vibratorSupported) {
            _messages.tryEmit("本机没有可用的振动器")
            return
        }
        updateMetronome { it.copy(vibrateEnabled = enabled) }
    }

    // ------------------------------------------------------------ 静音模式参数

    /** 拖动时实时预览颜色，不写 DataStore。 */
    fun previewFlashColor(argb: Long) {
        _previewColor.value = argb and 0xFFFF_FFFFL
    }

    fun commitFlashColor(argb: Long) {
        val target = _previewColor.value ?: (argb and 0xFFFF_FFFFL)
        _previewColor.value = null
        updateMetronome { it.copy(flashColor = target) }
    }

    /** 点击预设色：直接落盘并清掉预览。 */
    fun setFlashColor(argb: Long) {
        _previewColor.value = null
        val color = argb and 0xFFFF_FFFFL
        updateMetronome { it.copy(flashColor = color) }
    }

    fun previewFlashBrightness(value: Float) {
        _previewBrightness.value = value.coerceIn(0.1f, 1f)
    }

    fun commitFlashBrightness(value: Float) {
        val target = _previewBrightness.value ?: value
        _previewBrightness.value = null
        updateMetronome { it.copy(flashBrightness = target.coerceIn(0.1f, 1f)) }
    }

    fun setFlashBrightness(value: Float) {
        _previewBrightness.value = null
        updateMetronome { it.copy(flashBrightness = value.coerceIn(0.1f, 1f)) }
    }

    fun setFlashStyle(style: String) {
        updateMetronome { it.copy(flashStyle = if (style == "flash") "flash" else "pulse") }
    }

    fun previewTorchStrength(value: Int) {
        val max = _torchState.value?.maxStrengthLevel?.coerceAtLeast(1) ?: 1
        val level = value.coerceIn(1, max)
        _torchStrengthPreview.value = level
        torch.setStrength(level)
    }

    fun commitTorchStrength(value: Int) {
        val target = _torchStrengthPreview.value ?: value
        _torchStrengthPreview.value = null
        setTorchStrength(target)
    }

    fun setTorchStrength(value: Int) {
        val max = _torchState.value?.maxStrengthLevel?.coerceAtLeast(1) ?: 1
        val level = value.coerceIn(1, max)
        torch.setStrength(level)
        updateMetronome { it.copy(torchStrength = level) }
    }

    fun setAvOffset(value: Int) {
        updateMetronome { it.copy(avOffsetMs = value.coerceIn(-100, 100)) }
    }

    /** 音量不在 MetronomeSettings 里，落在本模块的本地偏好中。 */
    fun setVolume(value: Float) {
        val next = value.coerceIn(0f, 1f)
        _volume.value = next
        player.setVolume(next)
        prefs.edit { putFloat(KEY_VOLUME, next) }
    }

    fun allowStrongFlash() {
        _strongFlashAck.value = true
        prefs.edit { putBoolean(KEY_STRONG_FLASH, true) }
    }

    fun dismissSafetyHint() {
        _safetyHintShown.value = true
        prefs.edit { putBoolean(KEY_SAFETY_HINT, true) }
    }

    // ------------------------------------------------------------ 内部

    private fun buildParams(): ClickParams {
        val current = settings.value
        val beats = current.beatsPerBar.coerceIn(1, 12)
        val pattern = List(beats) { index ->
            val fallback = if (index == 0) 2 else 1
            current.accentPattern.getOrElse(index) { fallback }.coerceIn(0, 2)
        }
        return ClickParams(
            bpm = _bpm.value,
            beatsPerBar = beats,
            beatUnit = current.beatUnit,
            subdivision = current.subdivision,
            accentPattern = pattern,
            soundEnabled = current.soundEnabled
        ).normalized()
    }

    private fun updateMetronome(transform: (MetronomeSettings) -> MetronomeSettings) {
        viewModelScope.launch {
            repository.updateSettings { app ->
                app.copy(metronome = transform(app.metronome))
            }
        }
    }

    private suspend fun ensureTorchProbed(): TorchCapabilities {
        _torchState.value?.let { return it }
        return torchProbeMutex.withLock {
            _torchState.value?.let { return@withLock it }
            val capabilities = withContext(Dispatchers.IO) { torch.probe() }
            _torchState.value = capabilities
            capabilities
        }
    }

    private suspend fun initializeTorch() {
        val capabilities = ensureTorchProbed()
        if (!capabilities.available) {
            if (settings.value.torchEnabled) {
                _messages.emit(capabilities.error ?: "闪光灯不可用，已关闭闪光灯节拍")
                updateMetronome { it.copy(torchEnabled = false) }
            }
            return
        }
        if (settings.value.torchEnabled) {
            if (!capabilities.supportsStrengthControl) {
                _messages.emit("本机不支持闪光灯亮度调节，已使用开/关模式")
            }
            if (settings.value.torchStrength > capabilities.maxStrengthLevel) {
                updateMetronome { it.copy(torchStrength = capabilities.maxStrengthLevel.coerceAtLeast(1)) }
            }
        }
    }

    private fun startBeatDispatch() {
        dispatchJob?.cancel()
        dispatchJob = viewModelScope.launch {
            val scope = this
            player.events.collect { event ->
                if (event.level <= 0) return@collect
                val current = settings.value
                if (current.torchEnabled && event.subIndex == 0) {
                    scope.launch {
                        delayUntil(event.nanoTime)
                        torch.setStrength(_torchStrengthPreview.value ?: current.torchStrength)
                        torch.pulse(if (event.isAccent) 60L else 45L)
                    }
                }
                if (current.vibrateEnabled) {
                    scope.launch {
                        delayUntil(event.nanoTime)
                        vibrator.pulse(event.isAccent, event.subIndex > 0)
                    }
                }
            }
        }
    }

    private fun stopBeatDispatch() {
        dispatchJob?.cancel()
        dispatchJob = null
        torch.turnOff()
        vibrator.cancel()
    }

    /** 事件可能提前约 200 ms 到达，等到目标时刻再触发设备动作。 */
    private suspend fun delayUntil(nanoTime: Long) {
        val delta = nanoTime - System.nanoTime()
        if (delta in 1..1_500_000_000L) {
            delay(delta / 1_000_000L)
        }
    }

    override fun onCleared() {
        dispatchJob?.cancel()
        dispatchJob = null
        player.release()
        torch.release()
        vibrator.release()
        super.onCleared()
    }
}

