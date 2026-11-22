package com.gtool.core.acoustic

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 纯 Kotlin 高精度 FFT 与声学信号处理算子（无 Android 依赖，可独立 JVM 单测）：
 * - 原地迭代 Radix-2 FFT（支持至 65536 点）
 * - 去直流 + Hann 窗零填充幅度谱 / 线性功率谱
 * - 三点对数抛物线细化插值（规范 §3.1，0.1 Hz 级精度）
 * - 半功率带宽法（-3 dB）品质因数 Q 与粘性阻尼比 ζ
 * - 窄带解析信号包络法提取衰减常数 τ 与 Decay Q、瞬态起振时间 Attack Time
 */
object FftEngine {

    const val DEFAULT_SAMPLE_RATE = 48_000
    const val DEFAULT_FFT_SIZE = 65_536
    const val MIN_STORE_FREQ_HZ = 30.0
    const val MAX_STORE_FREQ_HZ = 6_000.0

    private class Plan(val size: Int) {
        val bitRev = IntArray(size)
        val cosTable = DoubleArray(size / 2)
        val sinTable = DoubleArray(size / 2)

        init {
            val bits = Integer.numberOfTrailingZeros(size)
            for (i in 0 until size) {
                bitRev[i] = Integer.reverse(i) ushr (32 - bits)
            }
            val factor = -2.0 * PI / size
            for (k in 0 until size / 2) {
                val angle = factor * k
                cosTable[k] = cos(angle)
                sinTable[k] = sin(angle)
            }
        }
    }

    private val plans = ConcurrentHashMap<Int, Plan>()

    private fun planFor(size: Int): Plan {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT 长度必须为 2 的幂：$size" }
        return plans.computeIfAbsent(size) { Plan(it) }
    }

    /**
     * 原地迭代 Cooley-Tukey Radix-2 FFT。
     * [inverse] = true 时执行逆变换（含 1/N 归一化）。
     */
    fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean = false) {
        val n = real.size
        require(imag.size == n) { "real 与 imag 长度不一致" }
        val plan = planFor(n)

        // 位反转重排
        for (i in 0 until n) {
            val j = plan.bitRev[i]
            if (j > i) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        val sign = if (inverse) -1.0 else 1.0
        var len = 2
        while (len <= n) {
            val half = len ushr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var tableIdx = 0
                for (j in 0 until half) {
                    val wr = plan.cosTable[tableIdx]
                    val wi = sign * plan.sinTable[tableIdx]
                    val uIdx = i + j
                    val vIdx = uIdx + half
                    val vr = real[vIdx]
                    val vi = imag[vIdx]
                    val tr = vr * wr - vi * wi
                    val ti = vr * wi + vi * wr
                    real[vIdx] = real[uIdx] - tr
                    imag[vIdx] = imag[uIdx] - ti
                    real[uIdx] = real[uIdx] + tr
                    imag[uIdx] = imag[uIdx] + ti
                    tableIdx += step
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1.0 / n
            for (i in 0 until n) {
                real[i] *= invN
                imag[i] *= invN
            }
        }
    }

    /**
     * 对单个敲击脉冲时域窗口加冲击测试专用非对称 Tukey 窗（前段短半余弦平滑引入 + 中段全平直 1.0 保留真实物理阻尼衰减 + 尾段半余弦淡出），
     * 并零填充至 [fftSize]，返回单边线性功率谱（幅值平方，按窗相干增益归一化至满幅正弦 = 1.0）。
     * 相比对称 Hann 窗，不会压制击发瞬态能量或人为展窄/拉高主峰 Q 值。
     * 返回长度为 `fftSize / 2 + 1`。
     */
    fun computePowerSpectrum(
        samples: FloatArray,
        fftSize: Int = DEFAULT_FFT_SIZE
    ): DoubleArray {
        val m = min(samples.size, fftSize)
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        if (m == 0) return DoubleArray(fftSize / 2 + 1)

        // 去除直流分量，避免低频谱泄漏
        var mean = 0.0
        for (i in 0 until m) mean += samples[i]
        mean /= m

        // 前段 3.5% 平滑引入（在 50 ms pre-trigger 内完成，不损伤主冲击）、尾段 12% 平滑淡出抑制截断泄漏
        val fadeIn = (m * 0.035).toInt().coerceAtLeast(1)
        val fadeOut = (m * 0.12).toInt().coerceAtLeast(1)
        val fadeOutStart = (m - fadeOut).coerceAtLeast(fadeIn)
        var winSum = 0.0

        for (i in 0 until m) {
            val w = when {
                i < fadeIn -> 0.5 * (1.0 - cos(PI * i / fadeIn))
                i >= fadeOutStart -> {
                    val u = (i - fadeOutStart).toDouble() / (m - 1 - fadeOutStart).coerceAtLeast(1)
                    0.5 * (1.0 + cos(PI * u))
                }
                else -> 1.0
            }
            winSum += w
            re[i] = (samples[i] - mean) * w
        }

        fft(re, im, inverse = false)

        // 单边谱幅值归一化：满幅正弦峰值对应 0.5 * Σw[i]
        val scale = max(winSum * 0.5, 1.0)
        val invScaleSq = 1.0 / (scale * scale)
        val half = fftSize / 2
        val power = DoubleArray(half + 1)
        power[0] = (re[0] * re[0] + im[0] * im[0]) * invScaleSq * 0.25
        for (k in 1..half) {
            power[k] = (re[k] * re[k] + im[k] * im[k]) * invScaleSq
        }
        return power
    }

    /**
     * 线性功率转为 dBFS（下限钳位 -130 dBFS）。
     */
    fun powerToDbfs(power: Double): Double {
        if (power <= 1e-13) return -130.0
        return (10.0 * log10(power)).coerceAtLeast(-130.0)
    }

    /**
     * dBFS 转为线性功率。
     */
    fun dbfsToPower(dbfs: Double): Double = Math.pow(10.0, dbfs / 10.0)

    /**
     * 从全长功率谱截取 [minHz, maxHz] 范围压缩存储为 dBFS 列表。
     */
    fun sliceDbfs(
        powerSpectrum: DoubleArray,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
        fftSize: Int = DEFAULT_FFT_SIZE,
        minHz: Double = MIN_STORE_FREQ_HZ,
        maxHz: Double = MAX_STORE_FREQ_HZ
    ): Pair<Int, List<Float>> {
        val freqStep = sampleRateHz.toDouble() / fftSize
        val startBin = (minHz / freqStep).toInt().coerceIn(1, powerSpectrum.lastIndex - 1)
        val endBin = (maxHz / freqStep).toInt().coerceIn(startBin, powerSpectrum.lastIndex)
        val list = ArrayList<Float>(endBin - startBin + 1)
        for (k in startBin..endBin) {
            list.add(powerToDbfs(powerSpectrum[k]).toFloat())
        }
        return startBin to list
    }

    data class RefinedPeak(
        val frequencyHz: Double,
        val amplitudeDb: Double,
        val deltaBins: Double
    )

    /**
     * 三点对数抛物线插值细化寻峰（规范 §3.1）。
     * 在对数幅度域（dB，与 ln(Y) 成正比）拟合三点抛物线顶点，精度达到 0.1 Hz 量级。
     */
    fun parabolicPeakRefine(
        magnitudesDb: List<Float>,
        localPeakIndex: Int,
        binStartIndex: Int,
        freqStepHz: Double
    ): RefinedPeak {
        val last = magnitudesDb.lastIndex
        val idx = localPeakIndex.coerceIn(0, last)
        val yCur = magnitudesDb[idx].toDouble()
        if (idx <= 0 || idx >= last) {
            return RefinedPeak((binStartIndex + idx) * freqStepHz, yCur, 0.0)
        }
        val yPrev = magnitudesDb[idx - 1].toDouble()
        val yNext = magnitudesDb[idx + 1].toDouble()
        val denom = yPrev - 2.0 * yCur + yNext
        if (denom >= -1e-9 || yCur < yPrev || yCur < yNext) {
            return RefinedPeak((binStartIndex + idx) * freqStepHz, yCur, 0.0)
        }
        val delta = (0.5 * (yPrev - yNext) / denom).coerceIn(-0.5, 0.5)
        val freq = (binStartIndex + idx + delta) * freqStepHz
        val amp = yCur - 0.25 * (yPrev - yNext) * delta
        return RefinedPeak(freq, amp, delta)
    }

    /**
     * 半功率带宽法（-3 dB）计算品质因数 Q = f_n / Δf_{-3dB}。
     * 当邻近模态导致单侧在到达 -3 dB 前出现波谷时，自动使用干净侧对称镜像带宽，避免模态重叠虚低估 Q。
     */
    fun computeBandwidthQ(
        magnitudesDb: List<Float>,
        localPeakIndex: Int,
        peakFreqHz: Double,
        peakDb: Double,
        binStartIndex: Int,
        freqStepHz: Double
    ): Double {
        if (magnitudesDb.size < 5) return 20.0
        val targetDb = peakDb - 3.0
        val maxWalkBins = ((peakFreqHz * 0.25) / freqStepHz).toInt().coerceIn(8, 90)

        var leftFreq: Double? = null
        var prevMag = peakDb
        for (step in 1..maxWalkBins) {
            val i = localPeakIndex - step
            if (i < 0) break
            val cur = magnitudesDb[i].toDouble()
            if (cur <= targetDb) {
                val span = (prevMag - cur).coerceAtLeast(1e-6)
                val frac = ((prevMag - targetDb) / span).coerceIn(0.0, 1.0)
                val exactIdx = (i + 1) - frac
                leftFreq = (binStartIndex + exactIdx) * freqStepHz
                break
            }
            if (cur > prevMag + 0.35 && step >= 2) {
                // 已经触底进入左侧相邻峰，提前中止
                break
            }
            prevMag = cur
        }

        var rightFreq: Double? = null
        prevMag = peakDb
        for (step in 1..maxWalkBins) {
            val i = localPeakIndex + step
            if (i > magnitudesDb.lastIndex) break
            val cur = magnitudesDb[i].toDouble()
            if (cur <= targetDb) {
                val span = (prevMag - cur).coerceAtLeast(1e-6)
                val frac = ((prevMag - targetDb) / span).coerceIn(0.0, 1.0)
                val exactIdx = (i - 1) + frac
                rightFreq = (binStartIndex + exactIdx) * freqStepHz
                break
            }
            if (cur > prevMag + 0.35 && step >= 2) {
                break
            }
            prevMag = cur
        }

        val bandwidth = when {
            leftFreq != null && rightFreq != null -> rightFreq - leftFreq
            leftFreq != null -> 2.0 * (peakFreqHz - leftFreq)
            rightFreq != null -> 2.0 * (rightFreq - peakFreqHz)
            else -> peakFreqHz / 12.0
        }.coerceAtLeast(freqStepHz * 0.6)

        return (peakFreqHz / bandwidth).coerceIn(3.0, 150.0)
    }

    data class EnvelopeMetrics(
        val attackTimeMs: Double,
        val decayTauMs: Double,
        val decayQ: Double
    )

    /**
     * 计算脉冲起振时间（Attack Time，ms）与窄带解析信号衰减包络（τ 与 Decay Q = π·f_n·τ）。
     */
    fun computeEnvelopeMetrics(
        samples: FloatArray,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE,
        triggerOffsetSamples: Int = (sampleRateHz * 0.05).toInt(),
        targetFreqHz: Double = 0.0
    ): EnvelopeMetrics {
        if (samples.size < sampleRateHz / 4) {
            return EnvelopeMetrics(attackTimeMs = 30.0, decayTauMs = 80.0, decayQ = 22.0)
        }

        // 1) 宽带短窗（2 ms）RMS 包络提取 Attack Time（10% -> 90% 峰值）
        val hop = (sampleRateHz * 0.002).toInt().coerceAtLeast(16)
        val envLen = samples.size / hop
        val env = DoubleArray(envLen)
        var maxEnv = 0.0
        var maxFrame = 0
        val searchStart = ((triggerOffsetSamples - sampleRateHz * 0.015).toInt() / hop).coerceAtLeast(0)
        val searchEnd = ((triggerOffsetSamples + sampleRateHz * 0.12).toInt() / hop).coerceAtMost(envLen - 1)
        for (f in 0 until envLen) {
            var sumSq = 0.0
            val base = f * hop
            for (k in 0 until hop) {
                val s = samples[base + k].toDouble()
                sumSq += s * s
            }
            val rms = sqrt(sumSq / hop)
            env[f] = rms
            if (f in searchStart..searchEnd && rms > maxEnv) {
                maxEnv = rms
                maxFrame = f
            }
        }

        var onsetFrame = searchStart
        val thresh10 = maxEnv * 0.10
        val thresh90 = maxEnv * 0.90
        for (f in maxFrame downTo searchStart) {
            if (env[f] <= thresh10) {
                onsetFrame = f
                break
            }
        }
        var reach90Frame = maxFrame
        for (f in onsetFrame..maxFrame) {
            if (env[f] >= thresh90) {
                reach90Frame = f
                break
            }
        }
        val attackMs = (((reach90Frame - onsetFrame).coerceAtLeast(1) * hop).toDouble() / sampleRateHz * 1000.0)
            .coerceIn(2.0, 120.0)

        // 2) 解析包络拟合衰减常数 τ（若指定了目标频率则走窄带希尔伯特解析信号，否则宽带包络）
        val tauSec = if (targetFreqHz > 40.0) {
            narrowbandDecayTauSeconds(samples, sampleRateHz, triggerOffsetSamples, targetFreqHz)
        } else {
            broadbandDecayTauSeconds(env, maxFrame, hop.toDouble() / sampleRateHz)
        }

        val tauMs = (tauSec * 1000.0).coerceIn(8.0, 800.0)
        val refFreq = if (targetFreqHz > 40.0) targetFreqHz else 185.0
        val decayQ = (PI * refFreq * (tauMs / 1000.0)).coerceIn(3.0, 150.0)
        return EnvelopeMetrics(attackTimeMs = attackMs, decayTauMs = tauMs, decayQ = decayQ)
    }

    private fun broadbandDecayTauSeconds(
        env: DoubleArray,
        peakFrame: Int,
        frameDurSec: Double
    ): Double {
        if (peakFrame >= env.lastIndex - 5) return 0.08
        val peakVal = env[peakFrame].coerceAtLeast(1e-6)
        val start = (peakFrame + (0.015 / frameDurSec).toInt()).coerceAtMost(env.lastIndex - 3)
        val end = (peakFrame + (0.35 / frameDurSec).toInt()).coerceAtMost(env.lastIndex)
        return fitExponentialTau(env, start, end, frameDurSec, peakVal * 0.08)
    }

    private fun narrowbandDecayTauSeconds(
        samples: FloatArray,
        sampleRateHz: Int,
        triggerOffsetSamples: Int,
        targetFreqHz: Double
    ): Double {
        val postStart = triggerOffsetSamples.coerceIn(0, (samples.size - 1024).coerceAtLeast(0))
        val avail = samples.size - postStart
        if (avail < 4096) return 0.08
        var n = 1
        while (n < avail && n < 32_768) n = n shl 1
        val copyLen = min(avail, n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until copyLen) re[i] = samples[postStart + i].toDouble()

        fft(re, im, inverse = false)

        // 构造单边带通解析信号（希尔伯特变换频域等价：正频乘 2，负频清零）
        val freqStep = sampleRateHz.toDouble() / n
        val halfBwHz = (targetFreqHz * 0.12).coerceIn(14.0, 32.0)
        val lowBin = ((targetFreqHz - halfBwHz) / freqStep).toInt().coerceAtLeast(1)
        val highBin = ((targetFreqHz + halfBwHz) / freqStep).toInt().coerceAtMost(n / 2 - 1)
        val bandWidthBins = (highBin - lowBin).coerceAtLeast(2).toDouble()

        for (k in 0 until n) {
            if (k in lowBin..highBin) {
                val u = (k - lowBin) / bandWidthBins
                // Raised-cosine 柔和带通，减少时域振铃
                val gain = 2.0 * (0.5 - 0.5 * cos(2.0 * PI * u))
                re[k] *= gain
                im[k] *= gain
            } else {
                re[k] = 0.0
                im[k] = 0.0
            }
        }

        fft(re, im, inverse = true)

        val hop = (sampleRateHz * 0.008).toInt().coerceAtLeast(64)
        val frameCount = (copyLen / hop).coerceAtLeast(1)
        val env = DoubleArray(frameCount)
        var maxEnv = 0.0
        var peakFrame = 0
        for (f in 0 until frameCount) {
            var sum = 0.0
            val base = f * hop
            for (k in 0 until hop) {
                sum += hypot(re[base + k], im[base + k])
            }
            val avg = sum / hop
            env[f] = avg
            if (f < frameCount / 2 && avg > maxEnv) {
                maxEnv = avg
                peakFrame = f
            }
        }
        if (maxEnv <= 1e-8) return 0.08
        val frameDurSec = hop.toDouble() / sampleRateHz
        val start = (peakFrame + 2).coerceAtMost(frameCount - 3)
        val end = (peakFrame + (0.40 / frameDurSec).toInt()).coerceAtMost(frameCount - 1)
        return fitExponentialTau(env, start, end, frameDurSec, maxEnv * 0.08)
    }

    /**
     * 对数域线性回归拟合 A(t) = A0 * exp(-t / τ)。
     */
    private fun fitExponentialTau(
        env: DoubleArray,
        startFrame: Int,
        endFrame: Int,
        frameDurSec: Double,
        floorAmplitude: Double
    ): Double {
        if (endFrame - startFrame < 3) return 0.08
        var count = 0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for (f in startFrame..endFrame) {
            val v = env[f]
            if (v < floorAmplitude) {
                if (count >= 4) break else continue
            }
            val x = (f - startFrame) * frameDurSec
            val y = ln(v.coerceAtLeast(1e-9))
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
            count++
        }
        if (count < 3) return 0.08
        val denom = count * sumXX - sumX * sumX
        if (abs(denom) < 1e-12) return 0.08
        val slope = (count * sumXY - sumX * sumY) / denom
        if (slope >= -0.5) return 0.25
        return (-1.0 / slope).coerceIn(0.01, 0.8)
    }
}
