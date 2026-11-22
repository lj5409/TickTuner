package com.gtool.core.acoustic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class TapCaptureProcessorTest {

    private val sr = 48_000
    private val fftSize = 16_384 // 单测用 16384 点加速执行，状态机逻辑完全一致

    private fun ambientChunk(durationSec: Double, amplitude: Double = 0.0005, seed: Long = 1L): FloatArray {
        val rng = Random(seed)
        val n = (sr * durationSec).toInt()
        return FloatArray(n) { ((rng.nextDouble() * 2.0 - 1.0) * amplitude).toFloat() }
    }

    private fun guitarTapPulse(
        durationSec: Double = 0.90,
        a0Hz: Double = 99.0,
        t1Hz: Double = 182.0,
        b1Hz: Double = 208.0,
        amplitude: Double = 0.35,
        noiseAmp: Double = 0.0004,
        seed: Long = 7L
    ): FloatArray {
        val rng = Random(seed)
        val n = (sr * durationSec).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            val env = (1.0 - exp(-t / 0.004)) * exp(-t / 0.065)
            val modal = 0.60 * sin(2.0 * PI * a0Hz * t) +
                1.00 * sin(2.0 * PI * t1Hz * t) +
                0.45 * sin(2.0 * PI * b1Hz * t)
            val noise = (rng.nextDouble() * 2.0 - 1.0) * noiseAmp
            ((amplitude * 0.5 * env * modal) + noise).toFloat()
        }
    }

    private fun feedInChunks(proc: TapCaptureProcessor, data: FloatArray): TapCaptureProcessor.Snapshot {
        val chunk = 1024
        var snap = proc.snapshot()
        var offset = 0
        while (offset < data.size) {
            val len = minOf(chunk, data.size - offset)
            val slice = data.copyOfRange(offset, offset + len)
            snap = proc.feed(slice, len)
            offset += len
        }
        return snap
    }

    @Test
    fun fiveConsistentTaps_passCalibrationAndAchieveHighCoherence() {
        val proc = TapCaptureProcessor(sampleRateHz = sr, fftSize = fftSize)
        proc.startCalibration()

        // 1.1 秒安静底噪校准
        var snap = feedInChunks(proc, ambientChunk(1.15))
        assertEquals(TapCaptureProcessor.Phase.LISTENING, snap.phase)
        assertTrue("底噪应很低: ${snap.noiseFloorDbfs}", snap.noiseFloorDbfs < -55.0)

        // 连续 5 次干净敲击，间隔安静尾段
        for (k in 1..5) {
            snap = feedInChunks(proc, guitarTapPulse(amplitude = 0.30 + k * 0.02, seed = k.toLong()))
            snap = feedInChunks(proc, ambientChunk(0.40, seed = 100L + k))
        }

        assertEquals(TapCaptureProcessor.Phase.READY, snap.phase)
        assertEquals(5, snap.acceptedTaps.size)
        assertTrue("一致敲击相干度应 >= 0.90，实际 ${snap.coherenceScore}", snap.coherencePassed)
        assertTrue(snap.coherenceScore >= 0.95)

        val spec = proc.buildPositionSpectrum(TapPosition.BRIDGE)
        assertEquals(TapPosition.BRIDGE, spec.position)
        assertEquals(5, spec.taps.size)
        assertTrue(spec.magnitudesDbfs.isNotEmpty())
    }

    @Test
    fun clippedTap_isRejectedWithFeedback() {
        val proc = TapCaptureProcessor(sampleRateHz = sr, fftSize = fftSize)
        proc.startCalibration()
        feedInChunks(proc, ambientChunk(1.15))

        // 满幅削波敲击
        val clipped = guitarTapPulse(amplitude = 3.0).map { it.coerceIn(-1f, 1f) }.toFloatArray()
        val snap = feedInChunks(proc, clipped)
        assertEquals(0, snap.acceptedTaps.size)
        assertFalse(snap.lastFeedback?.accepted ?: true)
        assertTrue("应提示削波: ${snap.lastFeedback?.message}", snap.lastFeedback?.message?.contains("削波") == true)
    }

    @Test
    fun doubleHitTap_isRejected() {
        val proc = TapCaptureProcessor(sampleRateHz = sr, fftSize = fftSize)
        proc.startCalibration()
        feedInChunks(proc, ambientChunk(1.15))

        val first = guitarTapPulse(durationSec = 0.95, amplitude = 0.35)
        // 在 150 ms 处叠加第二次同等力度连击
        val secondOffset = (sr * 0.15).toInt()
        val second = guitarTapPulse(durationSec = 0.70, amplitude = 0.32)
        for (i in second.indices) {
            val idx = secondOffset + i
            if (idx < first.size) first[idx] = (first[idx] + second[i]).coerceIn(-0.95f, 0.95f)
        }
        val snap = feedInChunks(proc, first)
        assertEquals(0, snap.acceptedTaps.size)
        assertFalse(snap.lastFeedback?.accepted ?: true)
        assertTrue("应提示二次连击: ${snap.lastFeedback?.message}", snap.lastFeedback?.message?.contains("连击") == true)
    }

    @Test
    fun unmutedSustainedString_isRejected() {
        val proc = TapCaptureProcessor(sampleRateHz = sr, fftSize = fftSize)
        proc.startCalibration()
        feedInChunks(proc, ambientChunk(1.15))

        // 持续不衰减的裸弦 E2 嗡鸣
        val sustained = FloatArray((sr * 0.95).toInt()) { i ->
            (0.22 * sin(2.0 * PI * 82.4 * i / sr)).toFloat()
        }
        val snap = feedInChunks(proc, sustained)
        assertEquals(0, snap.acceptedTaps.size)
        assertFalse(snap.lastFeedback?.accepted ?: true)
        assertTrue("应提示消音或持续噪声: ${snap.lastFeedback?.message}", snap.lastFeedback?.message?.contains("消音") == true)
    }

    @Test
    fun outlierSpectrumTap_isRejectedAndRemoveTapWorks() {
        val proc = TapCaptureProcessor(sampleRateHz = sr, fftSize = fftSize)
        proc.startCalibration()
        feedInChunks(proc, ambientChunk(1.15))

        // 收录 2 次正常敲击
        feedInChunks(proc, guitarTapPulse(seed = 1))
        feedInChunks(proc, ambientChunk(0.4))
        feedInChunks(proc, guitarTapPulse(seed = 2))
        var snap = feedInChunks(proc, ambientChunk(0.4))
        assertEquals(2, snap.acceptedTaps.size)

        // 喂入一次频率完全不同的误敲（模拟敲到琴颈或桌面，480 Hz 主峰）
        val outlier = guitarTapPulse(a0Hz = 430.0, t1Hz = 490.0, b1Hz = 540.0, seed = 99)
        snap = feedInChunks(proc, outlier)
        assertEquals("离群敲击不应被收录", 2, snap.acceptedTaps.size)
        assertFalse(snap.lastFeedback?.accepted ?: true)

        // 手动删除第 1 击后应剩 1 击
        snap = proc.removeTap(0)
        assertEquals(1, snap.acceptedTaps.size)
    }
}
