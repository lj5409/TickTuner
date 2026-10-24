package com.gtool.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * 麦克风采集：AudioRecord（48 kHz / 单声道 / PCM16），环形缓冲，按窗口把
 * 归一化后的 FloatArray（-1..1）回调给 [onWindow]。
 *
 * - 采集在独立高优先级线程；窗口回调在独立的单线程分析线程执行（不会阻塞采集）。
 * - 分析跟不上时只保留最新窗口，自动丢弃中间窗口，不会无限堆积。
 * - 优先 UNPROCESSED，失败回退 VOICE_RECOGNITION，再回退 DEFAULT。
 * - 声音数据只在本机内存中处理，不做任何落盘/上传。
 *
 * 生命周期：[start] -> [stop]（可重复 start/stop）-> [release]（终态）。
 */
class AudioCapture(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val hopSize: Int = DEFAULT_HOP_SIZE,
    private val onWindow: (FloatArray) -> Unit,
    private val onError: (String) -> Unit = {}
) {

    private val pendingWindow = AtomicReference<FloatArray?>(null)
    private var analysisExecutor: ExecutorService = newAnalysisExecutor()

    @Volatile
    private var running = false

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var captureThread: Thread? = null

    val isRunning: Boolean get() = running

    val sampleRateHz: Int get() = sampleRate

    /**
     * 启动采集。返回 false 表示初始化/启动失败，具体原因通过 [onError] 回调。
     */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (record != null) stop()
        if (analysisExecutor.isShutdown) analysisExecutor = newAnalysisExecutor()

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = if (minBufferSize > 0) {
            max(minBufferSize * 2, windowSize * BYTES_PER_SAMPLE)
        } else {
            max(windowSize * BYTES_PER_SAMPLE * 2, READ_CHUNK * BYTES_PER_SAMPLE * 4)
        }

        val newRecord = openStartedRecord(bufferBytes)
        if (newRecord == null) {
            reportError("无法打开麦克风：设备可能不支持 48 kHz 录音，或被其他应用占用")
            return false
        }

        record = newRecord
        pendingWindow.set(null)
        running = true
        val thread = Thread({ captureLoop(newRecord) }, "gtool-tuner-capture")
        captureThread = thread
        thread.start()
        return true
    }

    /**
     * 停止采集并释放 AudioRecord。会短暂等待采集线程退出（最多 200 ms）。
     */
    @Synchronized
    fun stop() {
        running = false
        val active = record
        record = null
        if (active != null) {
            runCatching {
                if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop()
            }
        }
        val thread = captureThread
        captureThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
        }
        if (active != null) runCatching { active.release() }
        pendingWindow.set(null)
    }

    /**
     * 释放全部资源（含分析线程）。释放后仍可再次 [start]（会重建线程池）。
     */
    @Synchronized
    fun release() {
        stop()
        pendingWindow.set(null)
        analysisExecutor.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    private fun openStartedRecord(bufferBytes: Int): AudioRecord? {
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        for (source in AUDIO_SOURCES) {
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    candidate.startRecording()
                    if (candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        return candidate
                    }
                }
            } catch (_: Throwable) {
                // 某些机型不支持 UNPROCESSED / 48 kHz，或该音源无法启动，继续尝试下一个
            }
            runCatching { candidate?.release() }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop(recorder: AudioRecord) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

        // 环形缓冲始终保存最近 windowSize 个采样。
        val ring = FloatArray(windowSize)
        var writeIndex = 0
        var filled = 0
        var samplesSinceEmit = 0
        val chunk = ShortArray(READ_CHUNK)

        try {
            while (running) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read < 0) {
                    if (running) reportError(readErrorMessage(read))
                    break
                }
                if (read == 0) continue

                for (i in 0 until read) {
                    ring[writeIndex] = chunk[i] / 32768f
                    writeIndex++
                    if (writeIndex == windowSize) writeIndex = 0
                    if (filled < windowSize) filled++
                }
                samplesSinceEmit += read
                if (filled == windowSize && samplesSinceEmit >= hopSize) {
                    samplesSinceEmit = 0
                    postWindow(snapshot(ring, writeIndex))
                }
            }
        } catch (t: Throwable) {
            if (running) reportError("麦克风异常：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun snapshot(ring: FloatArray, writeIndex: Int): FloatArray {
        val output = FloatArray(ring.size)
        for (i in ring.indices) {
            output[i] = ring[(writeIndex + i) % ring.size]
        }
        return output
    }

    private fun postWindow(window: FloatArray) {
        pendingWindow.set(window)
        try {
            analysisExecutor.execute {
                val latest = pendingWindow.getAndSet(null) ?: return@execute
                if (!running) return@execute
                try {
                    onWindow(latest)
                } catch (t: Throwable) {
                    reportError("音高分析失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
        } catch (_: RejectedExecutionException) {
            // release() 与回调竞争，忽略
        }
    }

    private fun reportError(message: String) {
        running = false
        runCatching { onError(message) }
    }

    private fun readErrorMessage(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "麦克风读取失败：状态无效，请重试"
        AudioRecord.ERROR_BAD_VALUE -> "麦克风读取失败：参数不被支持"
        AudioRecord.ERROR_DEAD_OBJECT -> "麦克风被系统回收，请重新进入调音器"
        AudioRecord.ERROR -> "麦克风读取失败（未知错误）"
        else -> "麦克风读取失败（错误码 $code）"
    }

    private fun newAnalysisExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "gtool-tuner-analysis").apply { isDaemon = true }
        }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Int = 48_000
        const val DEFAULT_WINDOW_SIZE: Int = 4096
        const val DEFAULT_HOP_SIZE: Int = 2048
        private const val READ_CHUNK = 1024
        private const val BYTES_PER_SAMPLE = 2
        private const val STOP_JOIN_TIMEOUT_MS = 200L

        /** 优先未处理音频（无 AGC/降噪，适合调音），再回退语音识别、默认。 */
        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )
    }
}
