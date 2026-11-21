package com.gtool.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 节拍器参数。全部字段都可以在播放中实时修改。
 *
 * @param bpm 每分钟拍数，范围 30..300，按“显示的拍”计数（6/8 时也是一拍一下）。
 * @param beatsPerBar 每小节拍数。
 * @param beatUnit 拍号分母（4 或 8），只用于 UI 显示。
 * @param subdivision 每拍细分数 1..4。
 * @param accentPattern 每拍重音等级：0 静音、1 普通、2 重音；按小节循环。
 * @param soundEnabled 是否实际发声。关闭后引擎继续按采样时钟走拍点（静音模式）。
 */
data class ClickParams(
    val bpm: Int = 120,
    val beatsPerBar: Int = 4,
    val beatUnit: Int = 4,
    val subdivision: Int = 1,
    val accentPattern: List<Int> = listOf(2, 1, 1, 1),
    val soundEnabled: Boolean = true
) {
    fun normalized(): ClickParams {
        val safeBpm = bpm.coerceIn(ClickPlayer.MIN_BPM, ClickPlayer.MAX_BPM)
        val safeBeats = beatsPerBar.coerceIn(1, 12)
        val safeUnit = if (beatUnit in 1..16) beatUnit else 4
        val safeSubdivision = subdivision.coerceIn(1, 4)
        val safePattern = if (accentPattern.isEmpty()) {
            List(safeBeats) { if (it == 0) 2 else 1 }
        } else {
            accentPattern.map { it.coerceIn(0, 2) }
        }
        return copy(
            bpm = safeBpm,
            beatsPerBar = safeBeats,
            beatUnit = safeUnit,
            subdivision = safeSubdivision,
            accentPattern = safePattern
        )
    }

    /** 第 [beatIndex] 拍（从 0 起、不按小节回绕）的重音等级。 */
    fun levelAt(beatIndex: Int): Int {
        val pattern = accentPattern
        if (pattern.isEmpty()) return 1
        return pattern[Math.floorMod(beatIndex, pattern.size)]
    }
}

/**
 * 一个拍点。由音频线程按采样时钟生成。
 *
 * @param beatIndex 自本次播放开始累计的拍序号（0 起）。
 * @param subIndex 该拍内的细分序号（0 = 正拍）。
 * @param isAccent 是否重音。
 * @param nanoTime 预计实际发声的 [System.nanoTime]（已计入音频缓冲与输出延迟）。
 * @param bpm 生成该拍点时使用的 BPM。
 * @param level 该拍的重音等级（0/1/2），供振动、闪光灯区分。
 */
data class BeatEvent(
    val beatIndex: Int,
    val subIndex: Int,
    val isAccent: Boolean,
    val nanoTime: Long,
    val bpm: Int,
    val level: Int = if (isAccent) 2 else 1
)

/**
 * 节拍器音频引擎：预渲染 click + 按采样计数精确调度。
 *
 * 实现要点：
 * - 48 kHz / PCM Float / 单声道 / MODE_STREAM，尽量申请 LOW_LATENCY。
 * - 音频线程维护绝对采样计数，提前约 200 ms 把 click 混入写入缓冲。
 * - 每个拍点通过 [events] 回调，nanoTime 由 AudioTrack 播放头/时间戳换算，
 *   调用方可以据此做画面/闪光/振动同步，而不是自己计时。
 * - 音频焦点：仅在有声播放时持有；被其他应用抢占时自动暂停并通知，重新获得时恢复。
 * - 必须调用 [release] 释放（在 ViewModel.onCleared / DisposableEffect 中）。
 */
class ClickPlayer(context: Context) {

    companion object {
        const val SAMPLE_RATE = 48_000
        const val MIN_BPM = 30
        const val MAX_BPM = 300

        /** 提前写入音频缓冲的时长。 */
        const val LOOKAHEAD_MS = 200

        /** 每个写入块 10 ms，块内按采样位置精确落点。 */
        private const val FRAMES_PER_BLOCK = 480
        private const val TAG = "ClickPlayer"
    }

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        onFocusChange(change)
    }

    private val _isPlaying = MutableStateFlow(false)

    /** 是否正在走拍（含静音模式）。 */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _events = MutableSharedFlow<BeatEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 拍点事件。可能提前约 200 ms 到达，请按 [BeatEvent.nanoTime] 对齐画面。 */
    val events: SharedFlow<BeatEvent> = _events.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 需要提示给用户的信息（焦点丢失、音频异常等）。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val lock = Any()

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var paramSnapshot = ClickParams()

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var volume = 0.9f

    private var lastParams = ClickParams()
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false
    private var released = false

    // 预渲染波形：首次 start 时才生成，避免 init 里做工作。
    private val accentWave: FloatArray by lazy {
        renderClick(frequency = 1600.0, amplitude = 0.92f, decaySeconds = 0.018, durationMs = 100, noise = 0.20f)
    }
    private val normalWave: FloatArray by lazy {
        renderClick(frequency = 1000.0, amplitude = 0.62f, decaySeconds = 0.016, durationMs = 90, noise = 0.12f)
    }
    private val subdivisionWave: FloatArray by lazy {
        renderClick(frequency = 2400.0, amplitude = 0.26f, decaySeconds = 0.008, durationMs = 45, noise = 0.08f)
    }

    /** 输出音量 0..1，实时生效。 */
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
    }

    /**
     * 开始播放。已在播放时等价于 [update]。
     * 静音模式下不申请音频焦点，避免打断用户正在播放的音乐。
     */
    fun start(params: ClickParams = paramSnapshot) {
        val p = params.normalized()
        lastParams = p
        paramSnapshot = p

        // 旧线程可能还在退出（例如被音频焦点打断后立刻恢复），先等它收尾。
        val existing = synchronized(lock) { thread }
        if (existing != null && existing !== Thread.currentThread()) {
            if (running) return
            runCatching { existing.join(300) }
        }

        synchronized(lock) {
            if (released) return
            if (thread != null) return
            if (p.soundEnabled && !requestFocus()) {
                _messages.tryEmit("无法获取音频焦点，可能与正在播放的应用冲突")
            }
            running = true
            pausedByFocus = false
            val worker = Thread({ audioLoop(p) }, "gtool-click-player")
            worker.priority = Thread.NORM_PRIORITY + 3
            thread = worker
            worker.start()
        }
    }

    /** 实时更新参数（BPM、拍号、细分、重音、是否发声）。 */
    fun update(params: ClickParams) {
        val p = params.normalized()
        val previous = paramSnapshot
        lastParams = p
        paramSnapshot = p
        if (previous.soundEnabled != p.soundEnabled) {
            if (p.soundEnabled) {
                if (running) requestFocus()
            } else {
                abandonFocus()
            }
        }
    }

    /** 暂停并释放音频焦点；再次 [start] 会从头开始走拍。 */
    fun pause() {
        pausedByFocus = false
        stopThread(releaseFocus = true)
    }

    /** 与 [pause] 相同，语义上表示彻底停止。 */
    fun stop() = pause()

    /** 释放全部资源，之后不可再使用。 */
    fun release() {
        released = true
        stopThread(releaseFocus = true)
    }

    // ---------------------------------------------------------------- 音频线程

    private fun audioLoop(initial: ClickParams) {
        var track: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            track = createTrack()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack 初始化失败")
            }
            activeTrack = track
            track.play()
            _isPlaying.value = true

            val block = FloatArray(FRAMES_PER_BLOCK)
            val pending = ArrayList<PendingEvent>(8)
            val timestamp = AudioTimestamp()
            val fallbackLatencyNanos = outputLatencyNanos()
            // click 波形比一个写入块长，用 carry 保存跨块尾音，避免衰减被截断。
            val carry = FloatArray(maxOf(accentWave.size, normalWave.size, subdivisionWave.size))

            var writtenFrames = 0L
            var nextEventSample = 0.0
            var beatIndex = 0
            var subIndex = 0
            var applied = initial
            var interval = intervalSamplesFor(applied)

            while (running) {
                val current = paramSnapshot
                if (current != applied) {
                    // 细分改变时从下一个拍点重新开始，避免出现在旧细分的中间位置。
                    if (current.subdivision != applied.subdivision) subIndex = 0
                    if (!current.soundEnabled && applied.soundEnabled) carry.fill(0f)
                    applied = current
                    interval = intervalSamplesFor(applied)
                }

                val blockStart = writtenFrames
                val blockEnd = blockStart + FRAMES_PER_BLOCK
                block.fill(0f)

                // 1) 先把上一块遗留的尾音叠加进来
                val carryCount = minOf(FRAMES_PER_BLOCK, carry.size)
                for (i in 0 until carryCount) block[i] += carry[i]
                // 2) carry 左移一个块，腾出空间给本块新 click 的尾巴
                if (carry.size > FRAMES_PER_BLOCK) {
                    java.lang.System.arraycopy(
                        carry,
                        FRAMES_PER_BLOCK,
                        carry,
                        0,
                        carry.size - FRAMES_PER_BLOCK
                    )
                    java.util.Arrays.fill(carry, carry.size - FRAMES_PER_BLOCK, carry.size, 0f)
                } else {
                    carry.fill(0f)
                }

                pending.clear()

                if (nextEventSample < blockStart.toDouble()) nextEventSample = blockStart.toDouble()

                while (nextEventSample < blockEnd.toDouble()) {
                    val samplePos = maxOf(nextEventSample.toLong(), blockStart)
                    val level = applied.levelAt(beatIndex)
                    if (applied.soundEnabled && level != 0) {
                        val wave = when {
                            subIndex > 0 -> subdivisionWave
                            level >= 2 -> accentWave
                            else -> normalWave
                        }
                        mixClick(block, carry, (samplePos - blockStart).toInt(), wave)
                    }
                    pending.add(
                        PendingEvent(
                            sample = samplePos,
                            event = BeatEvent(
                                beatIndex = beatIndex,
                                subIndex = subIndex,
                                isAccent = level >= 2,
                                nanoTime = 0L,
                                bpm = applied.bpm,
                                level = level
                            )
                        )
                    )
                    subIndex++
                    if (subIndex >= applied.subdivision) {
                        subIndex = 0
                        beatIndex++
                    }
                    nextEventSample += interval
                }

                for (i in block.indices) {
                    block[i] = block[i].coerceIn(-0.98f, 0.98f)
                }

                // 用播放头 + AudioTimestamp 把“采样位置”换算成现实时间。
                val headFrames = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                val now = System.nanoTime()
                val hasTimestamp = runCatching { track.getTimestamp(timestamp) }.getOrDefault(false)

                var baseFrame = headFrames
                var baseNanos = now + fallbackLatencyNanos
                if (hasTimestamp && timestamp.framePosition >= 0) {
                    val age = now - timestamp.nanoTime
                    if (age in 0..100_000_000L && timestamp.framePosition <= writtenFrames + FRAMES_PER_BLOCK) {
                        baseFrame = timestamp.framePosition
                        baseNanos = timestamp.nanoTime
                    }
                }

                var offset = 0
                while (offset < block.size) {
                    val written = track.write(block, offset, block.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) {
                        throw IllegalStateException("AudioTrack.write 返回 $written")
                    }
                    offset += written
                }
                writtenFrames = blockEnd

                if (running) {
                    for (item in pending) {
                        val deltaFrames = item.sample - baseFrame
                        val nanos = baseNanos + deltaFrames * 1_000_000_000L / SAMPLE_RATE
                        _events.tryEmit(item.event.copy(nanoTime = nanos))
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) {
                Log.e(TAG, "音频线程异常", t)
                _messages.tryEmit("音频引擎异常：${t.message ?: t.javaClass.simpleName}")
            }
        } finally {
            running = false
            _isPlaying.value = false
            activeTrack = null
            try {
                track?.pause()
            } catch (_: Throwable) {
            }
            try {
                track?.flush()
            } catch (_: Throwable) {
            }
            try {
                track?.release()
            } catch (_: Throwable) {
            }
            synchronized(lock) {
                if (thread === Thread.currentThread()) thread = null
            }
        }
    }

    private fun createTrack(): AudioTrack {
        val frameBytes = 4 // mono float
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val lookaheadBytes = (SAMPLE_RATE / 1000 * LOOKAHEAD_MS) * frameBytes
        val safeMinBuffer = if (minBuffer > 0) minBuffer else lookaheadBytes
        val bufferBytes = maxOf(safeMinBuffer * 2, lookaheadBytes)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (t: Throwable) {
            // 个别 ROM 拒绝低延迟模式时回退到普通模式。
            Log.w(TAG, "低延迟 AudioTrack 创建失败，回退", t)
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }
    }

    private fun stopThread(releaseFocus: Boolean) {
        running = false
        // 先暂停 AudioTrack，让可能阻塞的 write(WRITE_BLOCKING) 尽快返回。
        runCatching { activeTrack?.pause() }
        val worker = synchronized(lock) { thread }
        if (worker != null && worker !== Thread.currentThread()) {
            try {
                worker.join(400)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        _isPlaying.value = false
        if (releaseFocus) abandonFocus()
    }

    // ---------------------------------------------------------------- 音频焦点

    private fun requestFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = if (granted) request else null
        return granted
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
        pausedByFocus = false
    }

    private fun onFocusChange(change: Int) {
        if (!paramSnapshot.soundEnabled) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (running) {
                    pausedByFocus = true
                    running = false
                    _messages.tryEmit("音频焦点被其他应用占用，已暂停")
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocus && !released) {
                    pausedByFocus = false
                    mainHandler.post { start(lastParams) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 波形

    /**
     * 生成一个 click：5 ms 线性起音 + 指数衰减 + 极短噪声瞬态。
     * 波形全部预渲染，播放时只做加法混音。
     */
    private fun renderClick(
        frequency: Double,
        amplitude: Float,
        decaySeconds: Double,
        durationMs: Int,
        noise: Float
    ): FloatArray {
        val length = (SAMPLE_RATE * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val attack = (SAMPLE_RATE * 0.005).toInt().coerceAtLeast(1)
        val noiseSamples = (SAMPLE_RATE * 0.004).toInt().coerceAtLeast(1)
        val random = java.util.Random(frequency.toLong() * 31L + 17L)
        val out = FloatArray(length)
        for (i in 0 until length) {
            val time = i.toDouble() / SAMPLE_RATE
            val attackEnv = if (i < attack) i.toDouble() / attack else 1.0
            val decayEnv = if (i < attack) {
                1.0
            } else {
                exp(-(time - attack.toDouble() / SAMPLE_RATE) / decaySeconds)
            }
            var value = sin(2.0 * PI * frequency * time) * decayEnv
            if (i < noiseSamples) {
                value += (random.nextFloat() * 2f - 1f) * noise * (1.0 - i.toDouble() / noiseSamples)
            }
            out[i] = (value * amplitude * attackEnv).toFloat()
        }
        return out
    }

    /**
     * 把一个 click 波形混入当前块；超出块长度的部分写入 [carry]，
     * 由下一块继续播放，保证 60 ms 的衰减尾音完整。
     */
    private fun mixClick(destination: FloatArray, carry: FloatArray, offset: Int, source: FloatArray) {
        if (offset < 0 || offset >= destination.size) return
        val gain = volume
        for (i in source.indices) {
            val position = offset + i
            val value = source[i] * gain
            if (position < destination.size) {
                destination[position] += value
            } else {
                val carryIndex = position - destination.size
                if (carryIndex < carry.size) carry[carryIndex] += value
            }
        }
    }

    private fun intervalSamplesFor(params: ClickParams): Double =
        SAMPLE_RATE * 60.0 / params.bpm / params.subdivision

    /**
     * 估算输出延迟（仅用于 AudioTimestamp 尚不可用的启动阶段）。
     * 用每缓冲帧数估算硬件缓冲时长，再加系统/HAL 的保守余量，最少按 20 ms 计。
     */
    private fun outputLatencyNanos(): Long {
        val frames = runCatching {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        }.getOrNull() ?: 0
        val bufferMillis = if (frames > 0) frames * 1000.0 / SAMPLE_RATE else 0.0
        val millis = maxOf(bufferMillis, 20.0)
        return millis.coerceIn(5.0, 200.0).toLong() * 1_000_000L
    }

    private data class PendingEvent(val sample: Long, val event: BeatEvent)
}

