package com.gtool.core.audio

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 一次 YIN 检测的结果。
 *
 * @param frequency 基频（Hz）
 * @param confidence 置信度 0..1（= 1 - 最小 CMND 值），越大越可靠
 * @param windowSize 实际参与计算的采样点数
 */
data class PitchResult(
    val frequency: Double,
    val confidence: Double,
    val windowSize: Int
)

/**
 * 纯 Kotlin 的 YIN 基频检测器（差分函数 + 累积均值归一化 + 抛物线插值）。
 *
 * 该文件不依赖任何 Android API，可直接在 JVM 单元测试里使用。调用方需要保证传入的
 * 采样是单声道、且采样率与构造参数一致。
 *
 * 窗口策略（自适应）：
 *  - 先用 2048 点做一次快速检测；结果高于 200 Hz 时直接采用（吉他高音弦足够）。
 *  - 低于 200 Hz（低音弦）或 2048 点无结果时，再用 4096 点重算，保证 E2（82 Hz）稳定。
 *
 * 对静音 / 白噪声等没有明显周期的信号返回 null。
 *
 * 本类内部有可复用的缓冲区，**非线程安全**，多线程使用时请在外层加锁。
 */
class PitchDetector(
    val sampleRate: Double = DEFAULT_SAMPLE_RATE,
    val minFrequency: Double = 60.0,
    val maxFrequency: Double = 1500.0,
    /** YIN 门限：CMND 首次低于该值即认为是基频周期。 */
    private val threshold: Double = 0.2,
    /** 低于该 RMS 视为静音。 */
    private val silenceRms: Double = 4e-4
) {

    private val lock = Any()
    private var scratch = FloatArray(0)
    private var cmnd = FloatArray(0)

    init {
        require(sampleRate > 0.0) { "sampleRate 必须为正数" }
        require(minFrequency > 0.0 && maxFrequency > minFrequency) { "频率范围不合法" }
        require(threshold > 0.0 && threshold < 1.0) { "threshold 必须在 (0,1) 内" }
    }

    /**
     * 检测 [samples] 末尾一个窗口的基频。
     * 返回 null 表示静音、噪声或没有足够清晰的周期性。
     */
    fun detect(samples: FloatArray): PitchResult? {
        synchronized(lock) {
            if (samples.size < SHORT_WINDOW) return null

            val shortResult = analyze(samples, SHORT_WINDOW)
            if (shortResult != null && shortResult.frequency >= FAST_PATH_MIN_FREQUENCY) {
                return shortResult
            }

            if (samples.size >= LONG_WINDOW) {
                val longResult = analyze(samples, LONG_WINDOW)
                if (longResult != null) {
                    if (shortResult == null) return longResult
                    // 低音区以长窗结果为准；两者明显矛盾时选置信度更高者，避免八度跳变。
                    val diffCents = abs(1200.0 * log2(longResult.frequency / shortResult.frequency))
                    return if (diffCents < 80.0 || longResult.confidence >= shortResult.confidence) {
                        longResult
                    } else {
                        shortResult
                    }
                }
            }
            return shortResult
        }
    }

    private fun analyze(samples: FloatArray, windowSize: Int): PitchResult? {
        val n = min(windowSize, samples.size)
        val start = samples.size - n
        ensureScratch(n)

        var sum = 0.0
        for (i in 0 until n) {
            val v = samples[start + i].toDouble()
            scratch[i] = v.toFloat()
            sum += v
        }
        val mean = sum / n
        var squareSum = 0.0
        for (i in 0 until n) {
            val centered = (scratch[i] - mean).toFloat()
            scratch[i] = centered
            squareSum += centered.toDouble() * centered
        }
        val rms = sqrt(squareSum / n)
        if (rms < silenceRms) return null

        val tauMin = max(2, (sampleRate / maxFrequency).toInt())
        val tauMax = min(n / 2, (sampleRate / minFrequency).toInt())
        if (tauMax < tauMin + 4) return null
        val compareLength = n - tauMax
        if (compareLength < tauMin * 2) return null
        ensureCmnd(tauMax + 2)

        var cumulative = 0.0
        var tau = 1
        var found = -1
        while (tau <= tauMax) {
            var difference = 0.0
            var j = 0
            while (j < compareLength) {
                val delta = (scratch[j] - scratch[j + tau]).toDouble()
                difference += delta * delta
                j++
            }
            cumulative += difference
            val value = if (cumulative > 1e-12) difference * tau / cumulative else 1.0
            cmnd[tau] = value.toFloat()

            if (tau >= tauMin) {
                if (found < 0) {
                    if (value < threshold) found = tau
                } else if (value < cmnd[tau - 1]) {
                    found = tau
                } else {
                    break // 越过局部极小值，found 停在上一个点
                }
            }
            tau++
        }

        if (found < 0) {
            // 没有低于门限的谷：找出全局最小，仍然高于门限就判为无音高（噪声）。
            var bestTau = -1
            var bestValue = Double.MAX_VALUE
            for (t in tauMin..tauMax) {
                val value = cmnd[t].toDouble()
                if (value < bestValue) {
                    bestValue = value
                    bestTau = t
                }
            }
            if (bestTau < 0 || bestValue > threshold) return null
            found = bestTau
        }

        val s0 = if (found - 1 >= 1) cmnd[found - 1].toDouble() else cmnd[found].toDouble()
        val s1 = cmnd[found].toDouble()
        val s2 = if (found + 1 <= tauMax) cmnd[found + 1].toDouble() else s1

        // 抛物线插值：y = a*x^2 + b*x + c，顶点偏移 = (s0 - s2) / (2 * (s0 - 2*s1 + s2))
        val denominator = s0 - 2.0 * s1 + s2
        var offset = if (abs(denominator) > 1e-9) (s0 - s2) / (2.0 * denominator) else 0.0
        offset = offset.coerceIn(-0.75, 0.75)
        val refinedTau = found + offset
        if (refinedTau <= 0.0) return null

        val frequency = sampleRate / refinedTau
        if (frequency < minFrequency || frequency > maxFrequency) return null

        val confidence = (1.0 - s1).coerceIn(0.0, 1.0)
        if (confidence < 1.0 - threshold) return null

        return PitchResult(frequency = frequency, confidence = confidence, windowSize = n)
    }

    private fun ensureScratch(size: Int) {
        if (scratch.size < size) scratch = FloatArray(size)
    }

    private fun ensureCmnd(size: Int) {
        if (cmnd.size < size) cmnd = FloatArray(size)
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE: Double = 48_000.0
        const val SHORT_WINDOW: Int = 2048
        const val LONG_WINDOW: Int = 4096

        /** 高于该频率时只用 2048 点窗口即可。 */
        const val FAST_PATH_MIN_FREQUENCY: Double = 200.0
    }
}
