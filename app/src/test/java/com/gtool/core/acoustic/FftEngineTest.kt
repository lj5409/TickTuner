package com.gtool.core.acoustic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class FftEngineTest {

    private val sr = FftEngine.DEFAULT_SAMPLE_RATE
    private val fftSize = FftEngine.DEFAULT_FFT_SIZE

    @Test
    fun fftAndInverseFft_roundTripMatchesOriginalSignal() {
        val n = 256
        val orig = DoubleArray(n) { i ->
            0.7 * sin(2.0 * PI * 7 * i / n) + 0.3 * sin(2.0 * PI * 23 * i / n)
        }
        val re = orig.copyOf()
        val im = DoubleArray(n)
        FftEngine.fft(re, im, inverse = false)
        FftEngine.fft(re, im, inverse = true)
        for (i in 0 until n) {
            assertEquals("index $i mismatch", orig[i], re[i], 1e-9)
            assertEquals("imag $i should be ~0", 0.0, im[i], 1e-9)
        }
    }

    @Test
    fun parabolicPeakRefine_achievesSub01HzPrecisionOnOffBinFrequencies() {
        val targets = listOf(98.43, 182.65, 312.18, 371.52)
        val len = 40_800 // 850 ms @ 48 kHz
        for (freq in targets) {
            val sig = FloatArray(len) { i ->
                (0.5 * sin(2.0 * PI * freq * i / sr)).toFloat()
            }
            val power = FftEngine.computePowerSpectrum(sig, fftSize)
            val (binStart, mags) = FftEngine.sliceDbfs(power, sr, fftSize)
            val step = sr.toDouble() / fftSize
            var maxIdx = 0
            for (i in mags.indices) {
                if (mags[i] > mags[maxIdx]) maxIdx = i
            }
            val refined = FftEngine.parabolicPeakRefine(mags, maxIdx, binStart, step)
            val err = abs(refined.frequencyHz - freq)
            assertTrue(
                "目标 ${freq}Hz 细化为 ${refined.frequencyHz}Hz，误差 ${err}Hz 超出 0.1Hz",
                err < 0.1
            )
            // 0.5 满幅正弦 => -6.02 dBFS 附近
            assertTrue("幅值 ${refined.amplitudeDb} dBFS 应接近 -6 dBFS", abs(refined.amplitudeDb - (-6.02)) < 0.4)
        }
    }

    @Test
    fun dampedResonator_bandwidthQAndDecayQMatchAnalyticalValues() {
        val f0 = 185.0
        val targetQ = 26.0
        // 指数衰减 sin(2π f0 t) * exp(-t / tau), 其中理论 Q = π * f0 * tau
        val tauSec = targetQ / (PI * f0)
        val pre = (sr * 0.05).toInt()
        val total = (sr * 0.85).toInt()
        val sig = FloatArray(total) { i ->
            if (i < pre) 0f
            else {
                val t = (i - pre).toDouble() / sr
                val rise = (1.0 - exp(-t / 0.004)) // ~9 ms 快速起振
                (0.6 * rise * exp(-t / tauSec) * sin(2.0 * PI * f0 * t)).toFloat()
            }
        }

        val power = FftEngine.computePowerSpectrum(sig, fftSize)
        val (binStart, mags) = FftEngine.sliceDbfs(power, sr, fftSize)
        val step = sr.toDouble() / fftSize
        var peakIdx = 0
        for (i in mags.indices) {
            if (mags[i] > mags[peakIdx]) peakIdx = i
        }
        val refined = FftEngine.parabolicPeakRefine(mags, peakIdx, binStart, step)
        assertTrue("共振峰频率误差过大：${refined.frequencyHz}", abs(refined.frequencyHz - f0) < 0.25)

        val bwQ = FftEngine.computeBandwidthQ(mags, peakIdx, refined.frequencyHz, refined.amplitudeDb, binStart, step)
        // Hann 窗展宽会使频域 -3dB Q 略低于纯理论值，但应在合理物理区间内
        assertTrue("半功率带宽 Q=$bwQ 偏离预期（目标约 $targetQ）", bwQ in 16.0..34.0)

        val env = FftEngine.computeEnvelopeMetrics(sig, sr, pre, refined.frequencyHz)
        assertTrue("Attack=${env.attackTimeMs}ms 应处于短促脉冲区间", env.attackTimeMs in 2.0..45.0)
        assertTrue("Decay Q=${env.decayQ} 应接近理论 Q=$targetQ", abs(env.decayQ - targetQ) < 8.0)
    }
}
