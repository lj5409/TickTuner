package com.gtool.core.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.max

/**
 * 面向敲击 FFT 分析的连续原始流式音频采集器（48 kHz / 单声道 / PCM16）：
 * - 优先 UNPROCESSED（绕过 AGC/降噪/滤波，保留真实木吉他频响），失败依次回退 VOICE_RECOGNITION、DEFAULT；
 * - 支持通过 [preferredDevice] 绑定外接 USB/有线指向麦克风或指定内置主麦；
 * - 音频数据完全在本机内存中处理，不落盘录音文件。
 */
class TapAudioCapture(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val onSamples: (FloatArray, Int) -> Unit,
    private val onError: (String) -> Unit = {}
) {

    @Volatile
    private var running = false

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var captureThread: Thread? = null

    val isRunning: Boolean get() = running

    @Synchronized
    fun start(preferredDevice: AudioDeviceInfo? = null): Boolean {
        if (running) return true
        if (record != null) stop()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = if (minBuf > 0) {
            max(minBuf * 3, READ_CHUNK * BYTES_PER_SAMPLE * 8)
        } else {
            READ_CHUNK * BYTES_PER_SAMPLE * 8
        }

        val recorder = openStartedRecord(bufferBytes, preferredDevice)
        if (recorder == null) {
            reportError("无法启动麦克风：请检查录音权限、外接麦克风连接或是否被其他应用占用")
            return false
        }

        record = recorder
        running = true
        val thread = Thread({ captureLoop(recorder) }, "gtool-acoustic-capture")
        captureThread = thread
        thread.start()
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        val active = record
        record = null
        if (active != null) {
            runCatching {
                if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    active.stop()
                }
            }
        }
        val thread = captureThread
        captureThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
        }
        if (active != null && (thread == null || !thread.isAlive)) {
            runCatching { active.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openStartedRecord(
        bufferBytes: Int,
        preferredDevice: AudioDeviceInfo?
    ): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        for (source in AUDIO_SOURCES) {
            var candidate: AudioRecord? = null
            try {
                candidate = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    if (preferredDevice != null) {
                        candidate.setPreferredDevice(preferredDevice)
                    }
                    candidate.startRecording()
                    if (candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        return candidate
                    }
                }
            } catch (_: Throwable) {
                // 尝试回退下一个音源
            }
            runCatching { candidate?.release() }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop(recorder: AudioRecord) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val pcm = ShortArray(READ_CHUNK)
        val floats = FloatArray(READ_CHUNK)
        try {
            while (running) {
                val read = recorder.read(pcm, 0, pcm.size)
                if (read < 0) {
                    if (running) reportError("麦克风读取异常（错误码 $read）")
                    break
                }
                if (read == 0) continue
                for (i in 0 until read) {
                    floats[i] = pcm[i] / 32768f
                }
                onSamples(floats, read)
            }
        } catch (t: Throwable) {
            if (running) reportError("音频采集异常：${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            runCatching { recorder.release() }
            if (record === recorder) record = null
        }
    }

    private fun reportError(msg: String) {
        running = false
        runCatching { onError(msg) }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
        private const val READ_CHUNK = 1024
        private const val BYTES_PER_SAMPLE = 2
        private const val STOP_JOIN_TIMEOUT_MS = 250L

        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )
    }
}
