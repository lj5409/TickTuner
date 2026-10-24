package com.gtool.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.gtool.core.music.Tuning
import com.gtool.core.music.Voicing
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh
import kotlin.random.Random

/** 和弦播放节奏型。 */
enum class ChordStyle(val label: String) {
    DOWN_STRUM("下扫"),
    UP_STRUM("上扫"),
    ARPEGGIO_53231323("分解")
}

/**
 * 和弦播放器：48 kHz 单声道 Float，低延迟 AudioTrack 流式混音。
 *
 * - 扫弦：低音弦 → 高音弦（上扫反向），每根弦间隔 30 ms。
 * - 分解：标准 53231323（5-3-2-3-1-3-2-3），每个音为八分音符。
 * - 事件按采样点调度；onPluck 的 timeMs 取自播放头的实际时间轴，回调在主线程触发。
 * - [play] 会自动停止上一次播放；[stop] 可随时停止；[release] 释放 AudioTrack 与音频焦点。
 *
 * onPluck 的 stringIndex 与 [Voicing.frets] 下标一致：0 = 最低音弦（6 弦）。
 */
class ChordPlayer(
    context: Context,
    private val synth: GuitarSynth = GuitarSynth()
) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val focusLock = Any()

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var running = false

    @Volatile
    private var released = false

    @Volatile
    private var focusRequest: AudioFocusRequest? = null

    private val callbackLock = Any()

    @Volatile
    private var sessionEndCallback: (() -> Unit)? = null

    private val playbackId = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var isPlaying: Boolean = false
        private set

    /**
     * 播放一个指法。
     *
     * @param capo 变调夹品位；发声音高 = 弦空弦 + capo + fret。
     * @param style 扫弦/分解节奏型。
     * @param bpm 速度 60–120（超出范围会限制到 30–240）。
     * @param loop 是否循环；循环时每个 4/4 小节重复一次。
     * @param onPluck 拨弦回调（主线程），timeMs 为该音在本次播放中的实际开始时间。
     * @param onComplete 非循环播放自然结束时的回调（主线程）。
     */
    fun play(
        voicing: Voicing,
        tuning: Tuning,
        capo: Int = 0,
        style: ChordStyle = ChordStyle.DOWN_STRUM,
        bpm: Int = 90,
        loop: Boolean = false,
        onPluck: (stringIndex: Int, timeMs: Long) -> Unit,
        onComplete: (() -> Unit)? = null
    ) {
        if (released) return
        stop()

        val id = playbackId.incrementAndGet()
        running = true
        isPlaying = true
        setSessionEndCallback(onComplete)
        requestAudioFocus(id)

        val config = PlaybackConfig(
            voicing = voicing,
            tuning = tuning,
            capo = capo.coerceIn(0, 12),
            style = style,
            bpm = bpm.coerceIn(30, 240),
            loop = loop
        )
        Thread({
            runPlayback(id, config, onPluck)
        }, "GTool-ChordPlayer").start()
    }

    /** 停止播放并丢弃当前 AudioTrack；可随后再次 [play]。 */
    fun stop() {
        stopInternal(notifySessionEnd = false)
    }

    private fun stopInternal(notifySessionEnd: Boolean) {
        val callback = synchronized(callbackLock) {
            val current = sessionEndCallback
            sessionEndCallback = null
            current
        }
        running = false
        isPlaying = false
        val stoppedId = playbackId.incrementAndGet()
        val currentTrack = track
        track = null
        if (currentTrack != null) {
            try {
                currentTrack.pause()
            } catch (_: Exception) {
            }
            try {
                currentTrack.flush()
            } catch (_: Exception) {
            }
            try {
                currentTrack.stop()
            } catch (_: Exception) {
            }
        }
        abandonAudioFocus()
        if (notifySessionEnd && callback != null) {
            mainHandler.post {
                // 若已开始新的播放，不要用旧会话的回调覆盖新状态。
                if (playbackId.get() == stoppedId) callback()
            }
        }
    }

    private fun setSessionEndCallback(callback: (() -> Unit)?) {
        synchronized(callbackLock) {
            sessionEndCallback = callback
        }
    }

    private fun clearSessionEndCallback(): (() -> Unit)? {
        return synchronized(callbackLock) {
            val current = sessionEndCallback
            sessionEndCallback = null
            current
        }
    }

    /** 释放播放器。之后不可再 play。 */
    fun release() {
        if (released) return
        released = true
        stop()
    }

    // ------------------------------------------------------------------
    // 播放线程
    // ------------------------------------------------------------------

    private fun runPlayback(
        id: Int,
        config: PlaybackConfig,
        onPluck: (stringIndex: Int, timeMs: Long) -> Unit
    ) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Exception) {
        }

        var localTrack: AudioTrack? = null
        var completed = false
        try {
            if (!running || id != playbackId.get()) return
            val tuning = config.tuning
            val frets = config.voicing.frets
            val stringCount = min(tuning.strings.size, frets.size)
            if (stringCount <= 0) {
                completed = true
                return
            }
            val sounded = (0 until stringCount).filter { frets[it] >= 0 }
            if (sounded.isEmpty()) {
                completed = true
                return
            }
            val midiOf = IntArray(stringCount) { index ->
                tuning.strings[index].midi + config.capo + frets[index]
            }

            // 先将本次要用的音色合成好，避免第一次拨弦时卡顿。
            for (index in sounded) synth.pcm(midiOf[index])
            if (!running || id != playbackId.get()) return

            val audioTrack = createAudioTrack()
            if (audioTrack == null) {
                completed = true
                return
            }
            localTrack = audioTrack
            track = audioTrack
            if (!running || id != playbackId.get()) return
            audioTrack.play()

            val sampleRate = GuitarSynth.SAMPLE_RATE
            val beatSamples = (sampleRate * 60.0 / config.bpm).toLong().coerceAtLeast(1L)
            val cycleSamples = beatSamples * 4
            val tailSamples = (sampleRate * TAIL_SECONDS).toLong()
            val totalSamples = if (config.loop) Long.MAX_VALUE else cycleSamples + tailSamples

            val strumInterval = (sampleRate * 0.030).toLong()
            val random = Random(System.nanoTime())
            val order = if (config.style == ChordStyle.UP_STRUM) sounded.reversed() else sounded
            val arpeggioPattern = intArrayOf(5, 3, 2, 3, 1, 3, 2, 3)

            fun nearestSounded(target: Int): Int {
                if (target in 0 until stringCount && frets[target] >= 0) return target
                var distance = 1
                while (distance < stringCount) {
                    val lower = target - distance
                    val upper = target + distance
                    if (lower >= 0 && frets[lower] >= 0) return lower
                    if (upper < stringCount && frets[upper] >= 0) return upper
                    distance++
                }
                return sounded.first()
            }

            val arpeggioOrder = arpeggioPattern.map { number ->
                nearestSounded(stringCount - number)
            }

            fun velocityFor(indexInPattern: Int): Float {
                val base = if (config.style == ChordStyle.ARPEGGIO_53231323) 0.70f else 0.76f
                val accent = if (indexInPattern == 0) 0.10f else 0f
                val jitter = (random.nextFloat() - 0.5f) * 0.16f
                return (base + accent + jitter).coerceIn(0.35f, 1f)
            }

            val pending = ArrayDeque<ScheduledPluck>()
            val uiQueue = ArrayDeque<ScheduledPluck>()
            val voices = ArrayList<Voice>()
            val mix = FloatArray(CHUNK_FRAMES)

            fun scheduleCycle(cycleIndex: Long) {
                val cycleStart = cycleIndex * cycleSamples
                if (config.style == ChordStyle.ARPEGGIO_53231323) {
                    val step = (beatSamples / 2).coerceAtLeast(1L)
                    arpeggioOrder.forEachIndexed { position, stringIndex ->
                        val sample = cycleStart + position * step
                        val event = ScheduledPluck(
                            sample = sample,
                            stringIndex = stringIndex,
                            midi = midiOf[stringIndex],
                            velocity = velocityFor(position)
                        )
                        pending.addLast(event)
                        uiQueue.addLast(event)
                    }
                } else {
                    order.forEachIndexed { position, stringIndex ->
                        val sample = cycleStart + position * strumInterval
                        val event = ScheduledPluck(
                            sample = sample,
                            stringIndex = stringIndex,
                            midi = midiOf[stringIndex],
                            velocity = velocityFor(position)
                        )
                        pending.addLast(event)
                        uiQueue.addLast(event)
                    }
                }
            }

            fun dispatchAudiblePlucks(headFrames: Long) {
                while (uiQueue.isNotEmpty() && uiQueue.first().sample <= headFrames) {
                    val event = uiQueue.removeFirst()
                    val timeMs = event.sample * 1000L / sampleRate
                    mainHandler.post {
                        if (id == playbackId.get()) onPluck(event.stringIndex, timeMs)
                    }
                }
            }

            var framesWritten = 0L
            var nextCycle = 0L

            while (running && id == playbackId.get()) {
                if (!config.loop && framesWritten >= totalSamples) {
                    val head = currentPlaybackHead(audioTrack, framesWritten)
                    dispatchAudiblePlucks(head)
                    if (head >= totalSamples) {
                        completed = true
                        break
                    }
                    Thread.sleep(4)
                    continue
                }

                val chunkStart = framesWritten
                val chunkEnd = chunkStart + CHUNK_FRAMES

                // 提前一个循环调度，保证写入线程始终有事件可播。
                val horizon = chunkEnd + cycleSamples + CHUNK_FRAMES
                while (true) {
                    if (!config.loop && nextCycle > 0) break
                    val cycleStart = nextCycle * cycleSamples
                    if (cycleStart >= horizon) break
                    if (!config.loop && cycleStart >= totalSamples) break
                    scheduleCycle(nextCycle)
                    nextCycle++
                }

                while (pending.isNotEmpty() && pending.first().sample < chunkEnd) {
                    val event = pending.removeFirst()
                    voices.add(
                        Voice(
                            startSample = event.sample,
                            pcm = synth.pcm(event.midi),
                            gain = VOICE_GAIN * (event.velocity / GuitarSynth.REFERENCE_VELOCITY)
                        )
                    )
                }

                java.util.Arrays.fill(mix, 0f)
                for (voiceIndex in voices.indices.reversed()) {
                    val voice = voices[voiceIndex]
                    val relative = chunkStart - voice.startSample
                    val destinationOffset = if (relative < 0) (-relative).toInt() else 0
                    if (destinationOffset >= CHUNK_FRAMES) continue
                    val sourceOffset = if (relative > 0) relative.toInt() else 0
                    if (sourceOffset >= voice.pcm.size) {
                        voices.removeAt(voiceIndex)
                        continue
                    }
                    val count = min(voice.pcm.size - sourceOffset, CHUNK_FRAMES - destinationOffset)
                    if (count > 0) {
                        for (i in 0 until count) {
                            mix[destinationOffset + i] += voice.pcm[sourceOffset + i] * voice.gain
                        }
                    }
                    if (sourceOffset + count >= voice.pcm.size) {
                        voices.removeAt(voiceIndex)
                    }
                }

                for (i in mix.indices) {
                    val value = mix[i]
                    mix[i] = tanh(value * SOFT_CLIP_DRIVE) * SOFT_CLIP_GAIN
                }

                var offset = 0
                while (offset < CHUNK_FRAMES && running && id == playbackId.get()) {
                    val written = audioTrack.write(
                        mix,
                        offset,
                        CHUNK_FRAMES - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                    if (written <= 0) break
                    offset += written
                }
                if (offset <= 0) break
                framesWritten += offset

                val head = currentPlaybackHead(audioTrack, framesWritten)
                dispatchAudiblePlucks(head)
            }
        } catch (throwable: Throwable) {
            if (running) {
                completed = true
                if (throwable !is IllegalStateException) {
                    Log.w(TAG, "和弦播放失败", throwable)
                }
            }
        } finally {
            if (localTrack != null) {
                try {
                    localTrack.pause()
                } catch (_: Exception) {
                }
                try {
                    localTrack.flush()
                } catch (_: Exception) {
                }
                try {
                    localTrack.stop()
                } catch (_: Exception) {
                }
                try {
                    localTrack.release()
                } catch (_: Exception) {
                }
                if (track === localTrack) track = null
            }
            if (id == playbackId.get()) {
                isPlaying = false
                abandonAudioFocus()
                if (completed) {
                    val callback = clearSessionEndCallback()
                    mainHandler.post {
                        if (id == playbackId.get()) callback?.invoke()
                    }
                }
            }
        }
    }

    private fun currentPlaybackHead(audioTrack: AudioTrack, framesWritten: Long): Long {
        return try {
            val raw = audioTrack.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            if (raw in 0..framesWritten) raw else 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun createAudioTrack(): AudioTrack? {
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                GuitarSynth.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            val bufferBytes = max(
                if (minBuffer > 0) minBuffer * 2 else 4_096,
                4_096
            )
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(GuitarSynth.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                null
            } else {
                created
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "无法创建 AudioTrack", throwable)
            null
        }
    }

    private fun requestAudioFocus(forPlaybackId: Int) {
        val manager = audioManager ?: return
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (forPlaybackId == playbackId.get()) {
                            stopInternal(notifySessionEnd = true)
                        }
                    }
                    else -> Unit
                }
            }
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener)
                .build()
            manager.requestAudioFocus(request)
            synchronized(focusLock) {
                focusRequest = request
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "申请音频焦点失败", throwable)
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        val request = synchronized(focusLock) {
            val current = focusRequest
            focusRequest = null
            current
        } ?: return
        try {
            manager.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }

    private data class PlaybackConfig(
        val voicing: Voicing,
        val tuning: Tuning,
        val capo: Int,
        val style: ChordStyle,
        val bpm: Int,
        val loop: Boolean
    )

    private data class ScheduledPluck(
        val sample: Long,
        val stringIndex: Int,
        val midi: Int,
        val velocity: Float
    )

    private data class Voice(
        val startSample: Long,
        val pcm: FloatArray,
        val gain: Float
    )

    private companion object {
        const val TAG = "ChordPlayer"
        const val CHUNK_FRAMES = 512
        const val TAIL_SECONDS = 3.5
        const val VOICE_GAIN = 0.42f
        const val SOFT_CLIP_DRIVE = 0.90f
        const val SOFT_CLIP_GAIN = 0.90f
    }
}
