package com.gtool.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 参考音播放：正弦基频 + 少量 2/3 次谐波，默认 2 秒。
 *
 * 手机扬声器几乎放不出 82 Hz（低音 E），加入谐波后低音弦的参考音也能清楚听到；
 * 高音弦则保持接近纯音，便于对比。
 *
 * 所有 AudioTrack 的创建/写入/释放在单独线程中完成；重复调用 [play] 会立即打断上一段
 * （通过递增 token 实现），不会叠加。
 */
class ToneGenerator(private val sampleRate: Int = DEFAULT_SAMPLE_RATE) {

    private val playToken = AtomicInteger(0)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gtool-tuner-tone").apply { isDaemon = true }
    }

    @Volatile
    private var activeTrack: AudioTrack? = null

    /**
     * 播放一个固定频率的参考音（定长）。
     * @param frequency 频率 Hz
     * @param durationMs 时长毫秒
     * @param volume 峰值音量 0..1
     */
    fun play(frequency: Double, durationMs: Int = 2000, volume: Float = 0.9f) {
        if (frequency <= 0.0 || durationMs <= 0) return
        val token = playToken.incrementAndGet()
        try {
            executor.execute { render(token, frequency, durationMs, volume.coerceIn(0f, 1f)) }
        } catch (_: Throwable) {
            // 已 release，忽略
        }
    }

    /**
     * 持续循环播放一个固定频率的参考音，直到调用 [stop] / [release] 或新的播放请求打断。
     * 采用相位连续环绕，零接缝咔哒声，停止时带短淡出避免爆音。
     */
    fun playLoop(frequency: Double, volume: Float = 0.9f) {
        if (frequency <= 0.0) return
        val token = playToken.incrementAndGet()
        try {
            executor.execute { renderLoop(token, frequency, volume.coerceIn(0f, 1f)) }
        } catch (_: Throwable) {
            // 已 release，忽略
        }
    }

    /** 立即停止当前参考音（下一帧内生效）。 */
    fun stop() {
        playToken.incrementAndGet()
    }

    /** 释放播放线程与 AudioTrack（由播放线程自行 release，避免与 write 并发竞争）。 */
    fun release() {
        playToken.incrementAndGet()
        val track = activeTrack
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
        executor.shutdown()
        val terminated = runCatching {
            executor.awaitTermination(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!terminated) {
            executor.shutdownNow()
        } else {
            val remaining = activeTrack
            activeTrack = null
            if (remaining != null) runCatching { remaining.release() }
        }
    }

    private fun render(token: Int, frequency: Double, durationMs: Int, volume: Float) {
        if (token != playToken.get()) return

        val totalFrames = (sampleRate.toDouble() * durationMs / 1000.0).toInt().coerceAtLeast(1)
        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferBytes = if (minBufferBytes > 0) {
            max(minBufferBytes * 2, sampleRate / 10 * BYTES_PER_FLOAT)
        } else {
            sampleRate / 5 * BYTES_PER_FLOAT
        }

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Throwable) {
            return
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return
        }
        activeTrack = track

        val playResult = runCatching { track.play() }
        if (playResult.isFailure) {
            runCatching { track.release() }
            if (activeTrack === track) activeTrack = null
            return
        }

        val fadeInFrames = (FADE_IN_SECONDS * sampleRate).toInt().coerceAtLeast(1)
        val fadeOutFrames = (FADE_OUT_SECONDS * sampleRate).toInt().coerceAtLeast(1)
        val chunk = FloatArray(CHUNK_FRAMES)
        val angularStep = 2.0 * PI * frequency / sampleRate

        try {
            var written = 0
            while (written < totalFrames && token == playToken.get()) {
                val count = min(CHUNK_FRAMES, totalFrames - written)
                for (i in 0 until count) {
                    val index = written + i
                    val attack = (index.toDouble() / fadeInFrames).coerceAtMost(1.0)
                    val release = ((totalFrames - index).toDouble() / fadeOutFrames).coerceAtMost(1.0)
                    val envelope = min(attack, release)
                    val phase = angularStep * index
                    // 基频 + 2 次、3 次谐波（归一化）：提高小扬声器上的可听度，同时保持音高清晰
                    val wave = (sin(phase) + HARMONIC_2 * sin(2.0 * phase) + HARMONIC_3 * sin(3.0 * phase)) /
                        (1.0 + HARMONIC_2 + HARMONIC_3)
                    chunk[i] = (wave * envelope * volume).toFloat()
                }

                var offset = 0
                while (offset < count && token == playToken.get()) {
                    val result = track.write(chunk, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                    if (result <= 0) break
                    offset += result
                }
                written += count
            }

            if (token == playToken.get()) {
                // stop() 会把已经写入的采样播放完，保证淡出尾巴不被截断
                runCatching { track.stop() }
                runCatching { Thread.sleep(TAIL_WAIT_MS) }
            } else {
                runCatching { track.pause() }
                runCatching { track.flush() }
            }
        } catch (_: Throwable) {
            // 被打断或设备异常，直接走清理
        } finally {
            runCatching { track.flush() }
            runCatching { track.release() }
            if (activeTrack === track) activeTrack = null
        }
    }

    private fun renderLoop(token: Int, frequency: Double, volume: Float) {
        if (token != playToken.get()) return

        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferBytes = if (minBufferBytes > 0) {
            max(minBufferBytes * 2, sampleRate / 10 * BYTES_PER_FLOAT)
        } else {
            sampleRate / 5 * BYTES_PER_FLOAT
        }

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (_: Throwable) {
            return
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return
        }
        activeTrack = track

        val playResult = runCatching { track.play() }
        if (playResult.isFailure) {
            runCatching { track.release() }
            if (activeTrack === track) activeTrack = null
            return
        }

        val fadeInFrames = (FADE_IN_SECONDS * sampleRate).toInt().coerceAtLeast(1)
        val chunk = FloatArray(CHUNK_FRAMES)
        val twoPi = 2.0 * PI
        val angularStep = twoPi * frequency / sampleRate
        val norm = 1.0 + HARMONIC_2 + HARMONIC_3

        try {
            var written = 0L
            var phase = 0.0
            while (token == playToken.get()) {
                for (i in 0 until CHUNK_FRAMES) {
                    val attack = if (written < fadeInFrames) {
                        (written.toDouble() / fadeInFrames).coerceAtMost(1.0)
                    } else {
                        1.0
                    }
                    val wave = (sin(phase) + HARMONIC_2 * sin(2.0 * phase) + HARMONIC_3 * sin(3.0 * phase)) / norm
                    chunk[i] = (wave * attack * volume).toFloat()
                    phase += angularStep
                    if (phase >= twoPi) phase -= twoPi
                    written++
                }
                var offset = 0
                while (offset < CHUNK_FRAMES && token == playToken.get()) {
                    val result = track.write(chunk, offset, CHUNK_FRAMES - offset, AudioTrack.WRITE_BLOCKING)
                    if (result <= 0) break
                    offset += result
                }
            }
            runCatching { track.pause() }
            runCatching { track.flush() }
        } catch (_: Throwable) {
            // 被打断或设备异常，直接走清理
        } finally {
            runCatching { track.flush() }
            runCatching { track.release() }
            if (activeTrack === track) activeTrack = null
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 48_000
        private const val BYTES_PER_FLOAT = 4
        private const val CHUNK_FRAMES = 240 // 5 ms @ 48 kHz
        private const val FADE_IN_SECONDS = 0.03
        private const val FADE_OUT_SECONDS = 0.15
        private const val TAIL_WAIT_MS = 120L
        private const val RELEASE_WAIT_MS = 250L
        private const val HARMONIC_2 = 0.30
        private const val HARMONIC_3 = 0.15
    }
}
