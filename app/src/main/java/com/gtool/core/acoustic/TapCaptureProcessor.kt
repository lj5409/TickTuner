package com.gtool.core.acoustic

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 实时敲击脉冲采集与有效性数据清洗状态机（纯 Kotlin，可独立 JVM 单测）：
 * 1) 敲击前 1 秒自动测量环境底噪（RMS + 频域噪底）
 * 2) 自适应能量门限（高出底噪 20 dB）截取击前 50 ms + 击后 800 ms 时域数据块
 * 3) 单次敲击质检：削波失真、二次连击（Double Hit）、低 SNR、疑似未消音琴弦/持续噪声、离群谱偏离
 * 4) 5 次有效敲击功率谱线性平均 + 多击间谱一致性等效相干度 γ²_eq（≥ 0.90 判定合格）
 */
class TapCaptureProcessor(
    private val sampleRateHz: Int = FftEngine.DEFAULT_SAMPLE_RATE,
    private val fftSize: Int = FftEngine.DEFAULT_FFT_SIZE,
    val targetTapCount: Int = 5,
    private val calibrationMs: Int = 1_000,
    private val preTriggerMs: Int = 50,
    private val postTriggerMs: Int = 800,
    private val cooldownMs: Int = 280,
    private val triggerAboveNoiseDb: Double = 20.0,
    private val minTriggerDbfs: Double = -46.0,
    private val minCoherenceThreshold: Double = 0.90
) {

    enum class Phase {
        IDLE,
        CALIBRATING,
        LISTENING,
        CAPTURING_TAP,
        COOLDOWN,
        READY
    }

    data class Feedback(
        val accepted: Boolean,
        val message: String,
        val timestampSeq: Int
    )

    data class Snapshot(
        val phase: Phase,
        val calibrationProgress: Float,
        val noiseFloorDbfs: Double,
        val noiseWarning: Boolean,
        val currentLevelDbfs: Double,
        val acceptedTaps: List<TapSampleMetrics>,
        val targetTapCount: Int,
        val coherenceScore: Double,
        val coherencePassed: Boolean,
        val lastFeedback: Feedback?,
        val previewSpectrumDbfs: List<Float>,
        val previewBinStart: Int,
        val freqStepHz: Double
    )

    private data class StoredTap(
        val metrics: TapSampleMetrics,
        val powerSpectrum: DoubleArray,
        val bandVector: DoubleArray
    )

    private val preSamples = (sampleRateHz * preTriggerMs / 1000).coerceAtLeast(256)
    private val postSamples = (sampleRateHz * postTriggerMs / 1000).coerceAtLeast(4096)
    private val totalTapSamples = preSamples + postSamples
    private val calibrationSamplesTarget = (sampleRateHz * calibrationMs / 1000).coerceAtLeast(4096)
    private val cooldownSamplesTarget = (sampleRateHz * cooldownMs / 1000).coerceAtLeast(1024)
    private val frameSize = (sampleRateHz * 0.004).toInt().coerceAtLeast(64) // 4 ms 能量探测窗

    private val ringBuffer = FloatArray(preSamples * 2)
    private var ringWrite = 0
    private var ringFilled = 0

    private var phase: Phase = Phase.IDLE
    private val calibrationBuffer = FloatArray(calibrationSamplesTarget)
    private var calibrationCollected = 0
    private var noiseFloorDbfs: Double = -75.0
    private var ambientPowerSpectrum: DoubleArray? = null
    private var currentLevelDbfs: Double = -90.0

    private val activeTapBuffer = FloatArray(totalTapSamples)
    private var activeTapCollected = 0
    private var cooldownRemaining = 0

    private val acceptedTaps = mutableListOf<StoredTap>()
    private var averagedPowerSpectrum: DoubleArray? = null
    private var currentCoherence: Double = 0.0
    private var feedbackSeq = 0
    private var lastFeedback: Feedback? = null

    private val freqStepHz: Double = sampleRateHz.toDouble() / fftSize
    private val bandLowBin: Int = (60.0 / freqStepHz).toInt().coerceAtLeast(1)
    private val bandHighBin: Int = (600.0 / freqStepHz).toInt().coerceAtMost(fftSize / 2)
    private val snrHighBin: Int = (500.0 / freqStepHz).toInt().coerceAtMost(fftSize / 2)

    @Synchronized
    fun startCalibration() {
        resetInternal(keepTaps = false)
        phase = Phase.CALIBRATING
    }

    @Synchronized
    fun recalibrateNoiseOnly() {
        resetInternal(keepTaps = true)
        phase = Phase.CALIBRATING
    }

    @Synchronized
    fun resetAll() {
        resetInternal(keepTaps = false)
        phase = Phase.IDLE
    }

    /** 删除单次指定序号的已收录敲击（用于用户点击单个胶囊重录最差的一击）。 */
    @Synchronized
    fun removeTap(index: Int): Snapshot {
        if (index in acceptedTaps.indices) {
            acceptedTaps.removeAt(index)
            recomputeAverageAndCoherence()
            if (phase == Phase.READY) {
                phase = Phase.LISTENING
            }
            pushFeedback(true, "已移除第 ${index + 1} 次敲击，请补敲")
        }
        return snapshot()
    }

    /** 推送来自麦克风或测试合成器的连续 PCM 采样块（-1..1）。 */
    @Synchronized
    fun feed(samples: FloatArray, count: Int = samples.size): Snapshot {
        val len = min(count, samples.size)
        if (len <= 0 || phase == Phase.IDLE || phase == Phase.READY) {
            return snapshot()
        }

        // 实时瞬时音量估算
        var sumSq = 0.0
        for (i in 0 until len) {
            val s = samples[i].toDouble()
            sumSq += s * s
        }
        val chunkRms = sqrt(sumSq / len)
        currentLevelDbfs = rmsToDbfs(chunkRms)

        var offset = 0
        while (offset < len && phase != Phase.READY && phase != Phase.IDLE) {
            when (phase) {
                Phase.CALIBRATING -> {
                    val need = calibrationSamplesTarget - calibrationCollected
                    val take = min(need, len - offset)
                    System.arraycopy(samples, offset, calibrationBuffer, calibrationCollected, take)
                    calibrationCollected += take
                    appendToRing(samples, offset, take)
                    offset += take
                    if (calibrationCollected >= calibrationSamplesTarget) {
                        finishCalibration()
                    }
                }

                Phase.LISTENING -> {
                    val step = min(frameSize, len - offset)
                    var frameSq = 0.0
                    for (i in 0 until step) {
                        val v = samples[offset + i].toDouble()
                        frameSq += v * v
                    }
                    val frameDb = rmsToDbfs(sqrt(frameSq / step))
                    val triggerThreshold = max(noiseFloorDbfs + triggerAboveNoiseDb, minTriggerDbfs)

                    if (frameDb >= triggerThreshold && ringFilled >= preSamples) {
                        // 冻结击前 preSamples 到 activeTapBuffer 前段
                        copyPreTrigger(activeTapBuffer)
                        val take = min(postSamples, len - offset)
                        System.arraycopy(samples, offset, activeTapBuffer, preSamples, take)
                        activeTapCollected = preSamples + take
                        appendToRing(samples, offset, take)
                        offset += take
                        phase = Phase.CAPTURING_TAP
                        if (activeTapCollected >= totalTapSamples) {
                            processCapturedTap()
                        }
                    } else {
                        appendToRing(samples, offset, step)
                        offset += step
                    }
                }

                Phase.CAPTURING_TAP -> {
                    val need = totalTapSamples - activeTapCollected
                    val take = min(need, len - offset)
                    System.arraycopy(samples, offset, activeTapBuffer, activeTapCollected, take)
                    activeTapCollected += take
                    appendToRing(samples, offset, take)
                    offset += take
                    if (activeTapCollected >= totalTapSamples) {
                        processCapturedTap()
                    }
                }

                Phase.COOLDOWN -> {
                    val take = min(cooldownRemaining, len - offset)
                    appendToRing(samples, offset, take)
                    cooldownRemaining -= take
                    offset += take
                    if (cooldownRemaining <= 0) {
                        phase = if (acceptedTaps.size >= targetTapCount) Phase.READY else Phase.LISTENING
                    }
                }

                Phase.IDLE, Phase.READY -> break
            }
        }
        return snapshot()
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val avg = averagedPowerSpectrum
        val (previewStart, previewDbfs) = if (avg != null) {
            FftEngine.sliceDbfs(avg, sampleRateHz, fftSize)
        } else {
            (FftEngine.MIN_STORE_FREQ_HZ / freqStepHz).toInt() to emptyList()
        }
        return Snapshot(
            phase = phase,
            calibrationProgress = (calibrationCollected.toFloat() / calibrationSamplesTarget).coerceIn(0f, 1f),
            noiseFloorDbfs = noiseFloorDbfs,
            noiseWarning = noiseFloorDbfs > -48.0,
            currentLevelDbfs = currentLevelDbfs,
            acceptedTaps = acceptedTaps.mapIndexed { idx, tap ->
                tap.metrics.copy(tapIndex = idx + 1)
            },
            targetTapCount = targetTapCount,
            coherenceScore = currentCoherence,
            coherencePassed = acceptedTaps.size >= targetTapCount && currentCoherence >= minCoherenceThreshold,
            lastFeedback = lastFeedback,
            previewSpectrumDbfs = previewDbfs,
            previewBinStart = previewStart,
            freqStepHz = freqStepHz
        )
    }

    /**
     * 导出当前点位的完整 [PositionSpectrum]（仅在已收集满 [targetTapCount] 次敲击后调用）。
     */
    @Synchronized
    fun buildPositionSpectrum(
        position: TapPosition,
        qualityOverride: Boolean = false
    ): PositionSpectrum {
        val avg = averagedPowerSpectrum ?: DoubleArray(fftSize / 2 + 1)
        val (binStart, mags) = FftEngine.sliceDbfs(avg, sampleRateHz, fftSize)
        val metricsList = acceptedTaps.mapIndexed { idx, s -> s.metrics.copy(tapIndex = idx + 1) }
        val avgAttack = metricsList.map { it.attackTimeMs }.average().takeIf { !it.isNaN() } ?: 35.0
        val avgTau = metricsList.map { it.decayTauMs }.average().takeIf { !it.isNaN() } ?: 90.0
        return PositionSpectrum(
            position = position,
            sampleRateHz = sampleRateHz,
            fftSize = fftSize,
            noiseFloorDbfs = noiseFloorDbfs,
            coherenceScore = currentCoherence,
            qualityOverride = qualityOverride,
            taps = metricsList,
            binStartIndex = binStart,
            freqStepHz = freqStepHz,
            magnitudesDbfs = mags,
            avgAttackTimeMs = avgAttack,
            avgDecayTauMs = avgTau
        )
    }

    private fun resetInternal(keepTaps: Boolean) {
        calibrationCollected = 0
        ringWrite = 0
        ringFilled = 0
        activeTapCollected = 0
        cooldownRemaining = 0
        if (!keepTaps) {
            acceptedTaps.clear()
            averagedPowerSpectrum = null
            currentCoherence = 0.0
            lastFeedback = null
        }
    }

    private fun finishCalibration() {
        var sumSq = 0.0
        for (i in 0 until calibrationSamplesTarget) {
            val s = calibrationBuffer[i].toDouble()
            sumSq += s * s
        }
        noiseFloorDbfs = rmsToDbfs(sqrt(sumSq / calibrationSamplesTarget)).coerceIn(-96.0, -20.0)
        // 记录环境频域本底谱（截取前 totalTapSamples 用于对照 SNR）
        val ambientSlice = calibrationBuffer.copyOf(min(calibrationSamplesTarget, totalTapSamples))
        ambientPowerSpectrum = FftEngine.computePowerSpectrum(ambientSlice, fftSize)
        phase = if (acceptedTaps.size >= targetTapCount) Phase.READY else Phase.LISTENING
        if (noiseFloorDbfs > -48.0) {
            pushFeedback(true, "底噪偏高 (${noiseFloorDbfs.toInt()} dBFS)，建议移至更安静环境")
        } else {
            pushFeedback(true, "环境安静 (${noiseFloorDbfs.toInt()} dBFS)，请开始轻敲")
        }
    }

    private fun appendToRing(samples: FloatArray, offset: Int, count: Int) {
        for (i in 0 until count) {
            ringBuffer[ringWrite] = samples[offset + i]
            ringWrite++
            if (ringWrite == ringBuffer.size) ringWrite = 0
            if (ringFilled < ringBuffer.size) ringFilled++
        }
    }

    private fun copyPreTrigger(dest: FloatArray) {
        var idx = ringWrite - preSamples
        if (idx < 0) idx += ringBuffer.size
        for (i in 0 until preSamples) {
            dest[i] = ringBuffer[idx]
            idx++
            if (idx == ringBuffer.size) idx = 0
        }
    }

    private fun processCapturedTap() {
        val tapData = activeTapBuffer.copyOf()
        cooldownRemaining = cooldownSamplesTarget
        phase = Phase.COOLDOWN

        // 1) 削波失真拦截
        if (detectClipping(tapData)) {
            pushFeedback(false, "敲击过重导致削波失真，请减轻力度重敲")
            return
        }

        // 2) 二次连击（Double Hit）拦截
        if (detectDoubleHit(tapData)) {
            pushFeedback(false, "检测到二次连击或手指蹭碰，请干脆单次轻敲")
            return
        }

        // 3) 持续背景噪声 / 疑似琴弦未消音检查
        if (detectUnmutedStringOrContinuousNoise(tapData)) {
            pushFeedback(false, "余振异常持续（疑似琴弦未消音或持续背景音），请检查消音布")
            return
        }

        // 计算全分辨率功率谱
        val power = FftEngine.computePowerSpectrum(tapData, fftSize)

        // 4) 主频带信噪比检查
        var maxPeakPower = 0.0
        var maxPeakBin = bandLowBin
        for (k in bandLowBin..snrHighBin) {
            if (power[k] > maxPeakPower) {
                maxPeakPower = power[k]
                maxPeakBin = k
            }
        }
        val peakSpecDbfs = FftEngine.powerToDbfs(maxPeakPower)
        val ambientFloorAtBand = ambientPowerSpectrum?.let { amb ->
            var sum = 0.0
            var c = 0
            for (k in bandLowBin..snrHighBin) {
                sum += amb[k]
                c++
            }
            FftEngine.powerToDbfs(sum / c.coerceAtLeast(1))
        } ?: noiseFloorDbfs
        val snrDb = peakSpecDbfs - min(ambientFloorAtBand, noiseFloorDbfs)
        if (snrDb < 18.0) {
            pushFeedback(false, "敲击信噪比不足 (${snrDb.toInt()} dB)，请稍加大力度或靠近麦克风")
            return
        }

        // 5) 与已有有效敲击的频谱一致性检查（防止误敲他处或偶发碰撞污染平均谱）
        val bandVec = extractNormalizedBandVector(power)
        if (acceptedTaps.isNotEmpty()) {
            val existingMean = meanBandVector(acceptedTaps)
            val simSq = cosineSimilaritySquared(bandVec, existingMean)
            if (simSq < 0.72) {
                pushFeedback(
                    false,
                    "该次敲击频谱偏离过大（一致性 ${(simSq * 100).toInt()}%），疑似敲偏或干扰，请重敲"
                )
                return
            }
        }

        // 计算时域包络指标
        var peakAbs = 0f
        for (s in tapData) {
            val a = abs(s)
            if (a > peakAbs) peakAbs = a
        }
        val peakDbfs = rmsToDbfs(peakAbs.toDouble())
        val mainPeakFreq = maxPeakBin * freqStepHz
        val envMetrics = FftEngine.computeEnvelopeMetrics(
            samples = tapData,
            sampleRateHz = sampleRateHz,
            triggerOffsetSamples = preSamples,
            targetFreqHz = mainPeakFreq
        )

        val metrics = TapSampleMetrics(
            tapIndex = acceptedTaps.size + 1,
            peakDbfs = peakDbfs,
            snrDb = snrDb,
            attackTimeMs = envMetrics.attackTimeMs,
            decayTauMs = envMetrics.decayTauMs,
            coherenceToMean = 1.0,
            accepted = true
        )
        acceptedTaps.add(StoredTap(metrics, power, bandVec))
        recomputeAverageAndCoherence()

        val countNow = acceptedTaps.size
        if (countNow >= targetTapCount) {
            phase = Phase.READY
            val cohPct = (currentCoherence * 100).toInt()
            if (currentCoherence >= minCoherenceThreshold) {
                pushFeedback(true, "已采集 $countNow/$targetTapCount 次，相干一致性 $cohPct% 达标")
            } else {
                pushFeedback(
                    false,
                    "已满 $countNow 次，但一致性 $cohPct% 低于 90%，建议点击低分胶囊重敲"
                )
            }
        } else {
            pushFeedback(true, "第 $countNow/$targetTapCount 次有效敲击已收录（SNR ${snrDb.toInt()} dB）")
        }
    }

    private fun detectClipping(data: FloatArray): Boolean {
        var run = 0
        for (v in data) {
            if (abs(v) >= 0.985f) {
                run++
                if (run >= 4) return true
            } else {
                run = 0
            }
        }
        return false
    }

    /**
     * 通过 8 ms 滑窗包络检测主脉冲后 30–380 ms 内是否出现明显的二次能量突升。
     */
    private fun detectDoubleHit(data: FloatArray): Boolean {
        val win = (sampleRateHz * 0.008).toInt().coerceAtLeast(64)
        val postLen = data.size - preSamples
        val frames = postLen / win
        if (frames < 12) return false

        val env = DoubleArray(frames)
        for (f in 0 until frames) {
            var sumSq = 0.0
            val base = preSamples + f * win
            for (k in 0 until win) {
                val s = data[base + k].toDouble()
                sumSq += s * s
            }
            env[f] = sqrt(sumSq / win)
        }

        // 主脉冲应落在前 6 帧（~48 ms）内
        val primarySearchEnd = min(6, frames - 1)
        var primaryPeak = 0.0
        var primaryIdx = 0
        for (f in 0..primarySearchEnd) {
            if (env[f] > primaryPeak) {
                primaryPeak = env[f]
                primaryIdx = f
            }
        }
        if (primaryPeak < 1e-5) return false

        // 从主脉冲后 4 帧（~32 ms）起追踪衰减最小值；若随后突升 > 2.4x（约 +7.6 dB）且超过主峰 38%，判为二次连击
        var minAfterPeak = primaryPeak
        val checkStart = primaryIdx + 4
        val checkEnd = min(frames - 1, primaryIdx + 48) // ~384 ms
        for (f in checkStart..checkEnd) {
            val cur = env[f]
            if (cur < minAfterPeak) {
                minAfterPeak = cur
            } else if (cur > minAfterPeak * 2.4 && cur > primaryPeak * 0.38) {
                return true
            }
        }
        return false
    }

    /**
     * 检查尾段（650–800 ms）能量是否异常不衰减（典型表现为裸弦嗡鸣或持续空调/音乐噪声）。
     */
    private fun detectUnmutedStringOrContinuousNoise(data: FloatArray): Boolean {
        val headStart = preSamples
        val headEnd = min(data.size, preSamples + (sampleRateHz * 0.06).toInt())
        val tailStart = min(data.size - 1, preSamples + (sampleRateHz * 0.65).toInt())
        val tailEnd = data.size
        if (tailEnd - tailStart < 256 || headEnd <= headStart) return false

        var headSq = 0.0
        for (i in headStart until headEnd) {
            val v = data[i].toDouble()
            headSq += v * v
        }
        val headRms = sqrt(headSq / (headEnd - headStart))
        if (headRms < 1e-5) return false

        var tailSq = 0.0
        for (i in tailStart until tailEnd) {
            val v = data[i].toDouble()
            tailSq += v * v
        }
        val tailRms = sqrt(tailSq / (tailEnd - tailStart))
        return (tailRms / headRms) > 0.48
    }

    private fun extractNormalizedBandVector(power: DoubleArray): DoubleArray {
        val len = bandHighBin - bandLowBin + 1
        val vec = DoubleArray(len)
        var normSq = 0.0
        for (i in 0 until len) {
            // 取幅值（sqrt(power)）以便能量比例更贴近多模态形态而非仅被单一最高峰统治
            val v = sqrt(power[bandLowBin + i].coerceAtLeast(0.0))
            vec[i] = v
            normSq += v * v
        }
        val norm = sqrt(normSq).coerceAtLeast(1e-12)
        for (i in 0 until len) vec[i] /= norm
        return vec
    }

    private fun meanBandVector(taps: List<StoredTap>): DoubleArray {
        val len = taps.first().bandVector.size
        val mean = DoubleArray(len)
        for (t in taps) {
            for (i in 0 until len) mean[i] += t.bandVector[i]
        }
        var normSq = 0.0
        for (i in 0 until len) {
            mean[i] /= taps.size
            normSq += mean[i] * mean[i]
        }
        val norm = sqrt(normSq).coerceAtLeast(1e-12)
        for (i in 0 until len) mean[i] /= norm
        return mean
    }

    private fun cosineSimilaritySquared(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = normA * normB
        if (denom <= 1e-24) return 0.0
        return ((dot * dot) / denom).coerceIn(0.0, 1.0)
    }

    private fun recomputeAverageAndCoherence() {
        if (acceptedTaps.isEmpty()) {
            averagedPowerSpectrum = null
            currentCoherence = 0.0
            return
        }
        val specLen = acceptedTaps.first().powerSpectrum.size
        val avg = DoubleArray(specLen)
        for (tap in acceptedTaps) {
            for (k in 0 until specLen) {
                avg[k] += tap.powerSpectrum[k]
            }
        }
        val invN = 1.0 / acceptedTaps.size
        for (k in 0 until specLen) avg[k] *= invN
        averagedPowerSpectrum = avg

        if (acceptedTaps.size == 1) {
            currentCoherence = 1.0
            acceptedTaps[0] = acceptedTaps[0].copy(
                metrics = acceptedTaps[0].metrics.copy(coherenceToMean = 1.0)
            )
            return
        }

        val meanVec = meanBandVector(acceptedTaps)
        var sumCoh = 0.0
        for (i in acceptedTaps.indices) {
            val coh = cosineSimilaritySquared(acceptedTaps[i].bandVector, meanVec)
            sumCoh += coh
            acceptedTaps[i] = acceptedTaps[i].copy(
                metrics = acceptedTaps[i].metrics.copy(coherenceToMean = coh)
            )
        }
        currentCoherence = (sumCoh / acceptedTaps.size).coerceIn(0.0, 1.0)
    }

    private fun pushFeedback(accepted: Boolean, message: String) {
        feedbackSeq++
        lastFeedback = Feedback(accepted, message, feedbackSeq)
    }

    private fun rmsToDbfs(rms: Double): Double {
        if (rms <= 1e-6) return -120.0
        return (20.0 * log10(rms)).coerceAtLeast(-120.0)
    }
}
