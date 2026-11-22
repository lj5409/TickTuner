package com.gtool.core.acoustic

import com.gtool.core.music.Note
import com.gtool.core.music.TuningLibrary
import com.gtool.core.music.centsFrom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 原声吉他敲击 FFT 声学特征解耦、缺陷自动化诊断、五维客观评级与四大曲风匹配引擎
 * （完整实现原声吉他声学分析规范 §§3–5，纯 Kotlin 可独立 JVM 单测）。
 */
object AcousticAnalyzer {

    private data class PeakCandidate(
        val localIndex: Int,
        val frequencyHz: Double,
        val amplitudeDb: Double,
        val prominenceDb: Double
    )

    fun analyze(
        spectra: Map<TapPosition, PositionSpectrum>,
        bodyShape: GuitarBodyShape = GuitarBodyShape.DREADNOUGHT,
        manualOverrides: Map<ModalId, Double> = emptyMap(),
        a4Hz: Double = 440.0,
        useFlats: Boolean = false
    ): AcousticReport {
        val bridge = spectra[TapPosition.BRIDGE]
        val dipoleL = spectra[TapPosition.DIPOLE_L]
        val dipoleV = spectra[TapPosition.DIPOLE_V]
        val back = spectra[TapPosition.BACK]
        val decouple = spectra[TapPosition.DECOUPLE]

        if (bridge == null || bridge.magnitudesDbfs.isEmpty()) {
            return AcousticReport(decoupleSkipped = decouple == null)
        }

        val peaks = LinkedHashMap<ModalId, DetectedPeak>()

        // 1) 提取背板独立单极子 B1（先提 B1 以便与正面谱图交叉验证，避免误把背板峰认作面板 T1）
        val b1Source = back ?: bridge
        val b1PeakRaw = extractModalPeak(
            modalId = ModalId.B1,
            primary = b1Source,
            reference = null,
            manualFreqHz = manualOverrides[ModalId.B1],
            a4Hz = a4Hz,
            useFlats = useFlats
        )

        // 2) 提取空气共振模态 A0（若做了封音孔解耦，则加权优先选择封音孔后明显衰减的真实气腔峰）
        val a0PeakRaw = extractA0Peak(
            bridge = bridge,
            decouple = decouple,
            manualFreqHz = manualOverrides[ModalId.A0],
            a4Hz = a4Hz,
            useFlats = useFlats
        )

        // 3) 提取面板主单极子 T1
        val t1PeakRaw = extractT1Peak(
            bridge = bridge,
            b1FreqHz = b1PeakRaw?.frequencyHz,
            manualFreqHz = manualOverrides[ModalId.T1],
            a4Hz = a4Hz,
            useFlats = useFlats
        )

        val t1RefDb = t1PeakRaw?.amplitudeDbfs ?: bridge.peakMagnitudeDbfs(140.0, 250.0)

        // 4) 提取横向偶极子 T2,1（对比侧翼谱图相对琴码中心谱图的差分增益）
        val t21Source = dipoleL ?: bridge
        val t21PeakRaw = extractModalPeak(
            modalId = ModalId.T21,
            primary = t21Source,
            reference = if (dipoleL != null) bridge else null,
            manualFreqHz = manualOverrides[ModalId.T21],
            a4Hz = a4Hz,
            useFlats = useFlats
        )

        // 5) 提取纵向偶极子 T1,2（对比下腹中线谱图相对琴码中心谱图的差分增益）
        val t12Source = dipoleV ?: bridge
        val t12PeakRaw = extractModalPeak(
            modalId = ModalId.T12,
            primary = t12Source,
            reference = if (dipoleV != null) bridge else null,
            manualFreqHz = manualOverrides[ModalId.T12],
            a4Hz = a4Hz,
            useFlats = useFlats
        )

        // 6) 检测横三极子 T3,1（可选，关注 400-520 Hz 尖刺）
        val t31PeakRaw = extractModalPeak(
            modalId = ModalId.T31,
            primary = bridge,
            reference = null,
            manualFreqHz = manualOverrides[ModalId.T31],
            a4Hz = a4Hz,
            useFlats = useFlats,
            minProminenceDb = 6.5
        )

        // 7) 封音孔解耦面板固有基频 T1_UNCOUPLED
        val t1UncoupledRaw = if (decouple != null && decouple.magnitudesDbfs.isNotEmpty()) {
            extractModalPeak(
                modalId = ModalId.T1_UNCOUPLED,
                primary = decouple,
                reference = null,
                manualFreqHz = manualOverrides[ModalId.T1_UNCOUPLED],
                a4Hz = a4Hz,
                useFlats = useFlats
            )
        } else null

        listOfNotNull(
            a0PeakRaw,
            t1PeakRaw,
            b1PeakRaw,
            t21PeakRaw,
            t12PeakRaw,
            t31PeakRaw,
            t1UncoupledRaw
        ).forEach { p ->
            peaks[p.modalId] = p.copy(relativeDb = p.amplitudeDbfs - t1RefDb)
        }

        val a0 = peaks[ModalId.A0]
        val t1 = peaks[ModalId.T1]
        val b1 = peaks[ModalId.B1]
        val t21 = peaks[ModalId.T21]
        val t12 = peaks[ModalId.T12]
        val t31 = peaks[ModalId.T31]
        val t1Uncoupled = peaks[ModalId.T1_UNCOUPLED]

        val backTopDiffHz = if (b1 != null && t1 != null) b1.frequencyHz - t1.frequencyHz else null
        val backTopDiffSemitones = if (b1 != null && t1 != null && t1.frequencyHz > 0.0) {
            12.0 * ln(b1.frequencyHz / t1.frequencyHz) / ln(2.0)
        } else null
        val dipoleRatio = if (t12 != null && t1 != null && t1.frequencyHz > 0.0) {
            t12.frequencyHz / t1.frequencyHz
        } else null
        val decoupleShiftHz = if (t1 != null && t1Uncoupled != null) {
            t1.frequencyHz - t1Uncoupled.frequencyHz
        } else null
        val a0DecoupleDropDb = if (a0 != null && decouple != null && decouple.magnitudesDbfs.isNotEmpty()) {
            val bridgeNorm = bridge.magnitudeAtFrequency(a0.frequencyHz) - bridge.peakMagnitudeDbfs(140.0, 250.0)
            val decNorm = decouple.magnitudeAtFrequency(a0.frequencyHz) - decouple.peakMagnitudeDbfs(140.0, 250.0)
            (bridgeNorm - decNorm)
        } else null

        val highFreqRollOffHz = estimateHighFreqRollOffHz(bridge, t1RefDb)

        // 8) 三项核心声学缺陷诊断 + T3,1 刺耳检测
        val defects = buildList {
            add(diagnoseWolfTone(t1, b1, a4Hz, useFlats))
            add(diagnoseBackTopCoupling(t1, b1, backTopDiffHz, backTopDiffSemitones))
            add(diagnoseNeckDeadSpot(bridge))
            if (t31 != null && t31.qFactor >= 32.0 && t31.relativeDb > -12.0 && t31.frequencyHz in 420.0..465.0) {
                add(
                    AcousticDefect(
                        code = "t31_harsh",
                        title = "横三极子中频尖峰提示（T₃,₁）",
                        severity = DefectSeverity.MODERATE,
                        summary = "440 Hz 附近检测到较尖锐的横三极子峰（${format1(t31.frequencyHz)} Hz，Q=${format1(t31.qFactor)}）",
                        detail = "在 A4 附近存在较孤立的 T₃,₁ 尖峰，大力扫弦时可能带出轻微空洞或塑料感杂音，可尝试磷青铜低/中张力琴弦柔化中高频。"
                    )
                )
            }
        }

        // 9) 五维加权音质评估（规范 §3.3）
        val dimensionScores = scoreDimensions(bridge, a0, t1, t12, dipoleRatio, t1RefDb)
        val overallScore = (dimensionScores.sumOf { it.score * it.weightPercent } / 100.0)
            .roundToInt()
            .coerceIn(0, 100)
        val qualityGrade = when {
            overallScore >= 88 -> "大师级"
            overallScore >= 78 -> "专业演奏级"
            overallScore >= 65 -> "进阶工坊级"
            else -> "基础练习级"
        }

        // 10) 四大主流曲风匹配推断（规范 §4.1–4.2）
        val styleMatches = matchPlayingStyles(
            a0Hz = a0?.frequencyHz,
            t1Hz = t1?.frequencyHz,
            backTopDiffHz = backTopDiffHz,
            t21RelDb = t21?.relativeDb,
            dipoleRatio = dipoleRatio,
            rollOffHz = highFreqRollOffHz,
            qT1 = t1?.qFactor,
            attackMs = bridge.avgAttackTimeMs
        )

        val notRecommendedHint = buildNotRecommendedHint(styleMatches, t1, a0, highFreqRollOffHz)
        val stringTensionAdvice = buildStringTensionAdvice(t1, bodyShape, styleMatches.firstOrNull()?.style)
        val voicingAdvice = buildVoicingAdvice(defects, t1, b1, decoupleShiftHz, decouple == null)

        val minCoh = spectra.values.minOfOrNull { it.coherenceScore } ?: 0.0
        val hasOverride = spectra.values.any { it.qualityOverride }

        return AcousticReport(
            peaks = peaks,
            backTopDiffHz = backTopDiffHz,
            backTopDiffSemitones = backTopDiffSemitones,
            dipoleRatio = dipoleRatio,
            decoupleShiftHz = decoupleShiftHz,
            a0DecoupleDropDb = a0DecoupleDropDb,
            highFreqRollOffHz = highFreqRollOffHz,
            defects = defects,
            dimensionScores = dimensionScores,
            overallScore = overallScore,
            qualityGrade = qualityGrade,
            styleMatches = styleMatches,
            notRecommendedHint = notRecommendedHint,
            stringTensionAdvice = stringTensionAdvice,
            voicingAdvice = voicingAdvice,
            decoupleSkipped = decouple == null,
            hasQualityOverride = hasOverride,
            minCoherence = minCoh
        )
    }

    /**
     * 查找指定频段内的显著局部极大值并做三点对数抛物线细化。
     */
    private fun findProminentPeaks(
        spectrum: PositionSpectrum,
        minHz: Double,
        maxHz: Double,
        minProminenceDb: Double = 2.0
    ): List<PeakCandidate> {
        val mags = spectrum.magnitudesDbfs
        if (mags.size < 5) return emptyList()
        val start = spectrum.localIndexForFrequency(minHz).coerceAtLeast(1)
        val end = spectrum.localIndexForFrequency(maxHz).coerceAtMost(mags.lastIndex - 1)
        if (end <= start) return emptyList()

        val halfWindowBins = (18.0 / spectrum.freqStepHz).toInt().coerceIn(4, 30)
        val results = mutableListOf<PeakCandidate>()
        for (i in start..end) {
            val cur = mags[i]
            if (cur >= mags[i - 1] && cur >= mags[i + 1] && (cur > mags[i - 1] || cur > mags[i + 1])) {
                // 计算局部显著度（相对左右半窗最小值的抬升幅度）
                var leftMin = cur
                for (j in max(0, i - halfWindowBins) until i) {
                    if (mags[j] < leftMin) leftMin = mags[j]
                }
                var rightMin = cur
                for (j in (i + 1)..min(mags.lastIndex, i + halfWindowBins)) {
                    if (mags[j] < rightMin) rightMin = mags[j]
                }
                val prom = cur - max(leftMin, rightMin)
                if (prom >= minProminenceDb) {
                    val refined = FftEngine.parabolicPeakRefine(
                        magnitudesDb = mags,
                        localPeakIndex = i,
                        binStartIndex = spectrum.binStartIndex,
                        freqStepHz = spectrum.freqStepHz
                    )
                    results.add(PeakCandidate(i, refined.frequencyHz, refined.amplitudeDb, prom.toDouble()))
                }
            }
        }
        return results
    }

    private fun extractA0Peak(
        bridge: PositionSpectrum,
        decouple: PositionSpectrum?,
        manualFreqHz: Double?,
        a4Hz: Double,
        useFlats: Boolean
    ): DetectedPeak? {
        if (manualFreqHz != null) {
            return buildManualPeak(ModalId.A0, bridge, manualFreqHz, a4Hz, useFlats)
        }
        val candidates = findProminentPeaks(bridge, ModalId.A0.minHz, ModalId.A0.maxHz, minProminenceDb = 2.0)
        val chosen = if (candidates.isEmpty()) {
            fallbackMaxInRange(bridge, ModalId.A0.minHz, ModalId.A0.maxHz)
        } else {
            val bridgeRef = bridge.peakMagnitudeDbfs(140.0, 250.0)
            val decRef = decouple?.peakMagnitudeDbfs(140.0, 250.0)
            candidates.maxByOrNull { cand ->
                var score = cand.amplitudeDb + cand.prominenceDb * 0.45
                // 钢弦吉他 A0 通常集中于 90–115 Hz，适度偏好典型区间
                if (cand.frequencyHz in 90.0..115.0) score += 2.5
                // 若具备封音孔谱图，真实 A0 峰在封音孔后相对幅度会显著跌落
                if (decouple != null && decRef != null) {
                    val drop = (cand.amplitudeDb - bridgeRef) - (decouple.magnitudeAtFrequency(cand.frequencyHz) - decRef)
                    score += drop.coerceIn(-4.0, 18.0) * 0.6
                }
                score
            }
        } ?: return null
        return candidateToDetectedPeak(ModalId.A0, bridge, chosen, a4Hz, useFlats, isManual = false)
    }

    private fun extractT1Peak(
        bridge: PositionSpectrum,
        b1FreqHz: Double?,
        manualFreqHz: Double?,
        a4Hz: Double,
        useFlats: Boolean
    ): DetectedPeak? {
        if (manualFreqHz != null) {
            return buildManualPeak(ModalId.T1, bridge, manualFreqHz, a4Hz, useFlats)
        }
        val candidates = findProminentPeaks(bridge, ModalId.T1.minHz, ModalId.T1.maxHz, minProminenceDb = 2.0)
        val chosen = if (candidates.isEmpty()) {
            fallbackMaxInRange(bridge, ModalId.T1.minHz, ModalId.T1.maxHz)
        } else {
            candidates.maxByOrNull { cand ->
                var score = cand.amplitudeDb + cand.prominenceDb * 0.5
                if (cand.frequencyHz in 165.0..210.0) score += 2.0
                // 若背板 B1 明显更高频（>205 Hz）且该候选与 B1 重合，同时下方存在强主峰，略减权重避免混淆
                if (b1FreqHz != null && b1FreqHz > 205.0 && abs(cand.frequencyHz - b1FreqHz) < 5.0) {
                    score -= 4.0
                }
                score
            }
        } ?: return null
        return candidateToDetectedPeak(ModalId.T1, bridge, chosen, a4Hz, useFlats, isManual = false)
    }

    private fun extractModalPeak(
        modalId: ModalId,
        primary: PositionSpectrum,
        reference: PositionSpectrum?,
        manualFreqHz: Double?,
        a4Hz: Double,
        useFlats: Boolean,
        minProminenceDb: Double = 2.0
    ): DetectedPeak? {
        if (manualFreqHz != null) {
            return buildManualPeak(modalId, primary, manualFreqHz, a4Hz, useFlats)
        }
        val candidates = findProminentPeaks(primary, modalId.minHz, modalId.maxHz, minProminenceDb)
        val chosen = if (candidates.isEmpty()) {
            if (modalId == ModalId.T31) return null
            fallbackMaxInRange(primary, modalId.minHz, modalId.maxHz)
        } else {
            val primaryPeak = primary.peakMagnitudeDbfs(60.0, 600.0)
            val refPeak = reference?.peakMagnitudeDbfs(60.0, 600.0)
            candidates.maxByOrNull { cand ->
                var score = cand.amplitudeDb + cand.prominenceDb * 0.45
                if (reference != null && refPeak != null) {
                    // 定向激发差分增益：目标点位相对琴码中心抬升越明显，越可能是目标偶极模态
                    val relPrimary = cand.amplitudeDb - primaryPeak
                    val relRef = reference.magnitudeAtFrequency(cand.frequencyHz) - refPeak
                    val boost = (relPrimary - relRef).coerceIn(-6.0, 15.0)
                    score += boost * 0.7
                }
                score
            }
        } ?: return null
        return candidateToDetectedPeak(modalId, primary, chosen, a4Hz, useFlats, isManual = false)
    }

    /**
     * 人工微调共振峰：在用户点击频率 ±5 Hz 邻域内优先吸附最近局部极大值并做抛物线细化；
     * 若平坦无局部极大值则精确落在用户指定频率。
     */
    private fun buildManualPeak(
        modalId: ModalId,
        spectrum: PositionSpectrum,
        targetHz: Double,
        a4Hz: Double,
        useFlats: Boolean
    ): DetectedPeak {
        val mags = spectrum.magnitudesDbfs
        val centerIdx = spectrum.localIndexForFrequency(targetHz)
        val snapBins = (5.0 / spectrum.freqStepHz).toInt().coerceIn(2, 10)
        val lo = (centerIdx - snapBins).coerceAtLeast(1)
        val hi = (centerIdx + snapBins).coerceAtMost(mags.lastIndex - 1)

        var bestLocalMaxIdx = -1
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in lo..hi) {
            val cur = mags[i]
            if (cur >= mags[i - 1] && cur >= mags[i + 1] && cur > bestVal) {
                bestVal = cur
                bestLocalMaxIdx = i
            }
        }

        val candidate = if (bestLocalMaxIdx >= 0) {
            val refined = FftEngine.parabolicPeakRefine(
                mags, bestLocalMaxIdx, spectrum.binStartIndex, spectrum.freqStepHz
            )
            PeakCandidate(bestLocalMaxIdx, refined.frequencyHz, refined.amplitudeDb, 6.0)
        } else {
            val amp = spectrum.magnitudeAtFrequency(targetHz)
            PeakCandidate(centerIdx, targetHz, amp, 0.0)
        }
        return candidateToDetectedPeak(modalId, spectrum, candidate, a4Hz, useFlats, isManual = true)
    }

    private fun fallbackMaxInRange(
        spectrum: PositionSpectrum,
        minHz: Double,
        maxHz: Double
    ): PeakCandidate? {
        val mags = spectrum.magnitudesDbfs
        if (mags.isEmpty()) return null
        val start = spectrum.localIndexForFrequency(minHz)
        val end = spectrum.localIndexForFrequency(maxHz)
        var bestIdx = start
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in start..end) {
            if (mags[i] > bestVal) {
                bestVal = mags[i]
                bestIdx = i
            }
        }
        val refined = FftEngine.parabolicPeakRefine(
            mags, bestIdx, spectrum.binStartIndex, spectrum.freqStepHz
        )
        return PeakCandidate(bestIdx, refined.frequencyHz, refined.amplitudeDb, 0.0)
    }

    private fun candidateToDetectedPeak(
        modalId: ModalId,
        spectrum: PositionSpectrum,
        candidate: PeakCandidate,
        a4Hz: Double,
        useFlats: Boolean,
        isManual: Boolean
    ): DetectedPeak {
        val bwQ = FftEngine.computeBandwidthQ(
            magnitudesDb = spectrum.magnitudesDbfs,
            localPeakIndex = candidate.localIndex,
            peakFreqHz = candidate.frequencyHz,
            peakDb = candidate.amplitudeDb,
            binStartIndex = spectrum.binStartIndex,
            freqStepHz = spectrum.freqStepHz
        )
        val tauSec = (spectrum.avgDecayTauMs / 1000.0).coerceIn(0.015, 0.5)
        val decayQ = (Math.PI * candidate.frequencyHz * tauSec).coerceIn(3.0, 150.0)
        val dampingRatio = 1.0 / (2.0 * bwQ)
        val note = Note.fromFrequency(candidate.frequencyHz, a4Hz)
        val cents = centsFrom(candidate.frequencyHz, note.frequency(a4Hz))
        return DetectedPeak(
            modalId = modalId,
            frequencyHz = candidate.frequencyHz,
            amplitudeDbfs = candidate.amplitudeDb,
            relativeDb = 0.0,
            qFactor = bwQ,
            decayQ = decayQ,
            dampingRatio = dampingRatio,
            tauMs = spectrum.avgDecayTauMs,
            nearestNoteName = note.name(useFlats),
            centsOffset = cents,
            sourcePosition = spectrum.position,
            isManualOverride = isManual
        )
    }

    /**
     * 诊断 1：狼音（Wolf Tone）临界重合排查（规范 §3.2）。
     */
    private fun diagnoseWolfTone(
        t1: DetectedPeak?,
        b1: DetectedPeak?,
        a4Hz: Double,
        useFlats: Boolean
    ): AcousticDefect {
        if (t1 == null) {
            return AcousticDefect("wolf_tone", "狼音风险排查", DefectSeverity.MODERATE, "未检出面板 T₁ 主峰", "请确认琴码中心敲击数据完整。")
        }
        val t1Note = Note.fromFrequency(t1.frequencyHz, a4Hz)
        val t1DiffHz = abs(t1.frequencyHz - t1Note.frequency(a4Hz))
        val t1Frets = describeGuitarFrets(t1Note)

        val b1Note = b1?.let { Note.fromFrequency(it.frequencyHz, a4Hz) }
        val b1DiffHz = if (b1 != null && b1Note != null) abs(b1.frequencyHz - b1Note.frequency(a4Hz)) else Double.POSITIVE_INFINITY
        val b1Frets = b1Note?.let { describeGuitarFrets(it) } ?: ""

        val severeT1 = t1DiffHz <= 2.5 && t1.qFactor >= 35.0
        val severeB1 = b1 != null && b1DiffHz <= 2.0
        val moderateT1 = t1DiffHz <= 2.5 && t1.qFactor >= 22.0

        return when {
            severeT1 || severeB1 -> {
                val reasons = buildList {
                    if (severeT1) {
                        add("面板 T₁（${format1(t1.frequencyHz)} Hz，Q=${format1(t1.qFactor)}）极度贴近 ${t1Note.name(useFlats)}（${format1(t1Note.frequency(a4Hz))} Hz，${t1Frets}）")
                    }
                    if (severeB1 && b1Note != null) {
                        add("背板 B₁（${format1(b1!!.frequencyHz)} Hz）落入 ${b1Note.name(useFlats)}（${format1(b1Note.frequency(a4Hz))} Hz，${b1Frets}）±2.0 Hz 临界区")
                    }
                }.joinToString("；")
                AcousticDefect(
                    code = "wolf_tone",
                    title = "狼音风险排查",
                    severity = DefectSeverity.SEVERE,
                    summary = "高危：$reasons",
                    detail = "主共振峰与标准琴弦品位基频高度重合，演奏对应品位时易出现瞬间音量冲高但延音骤断、伴随颤抖拍频的能量短路现象。"
                )
            }

            moderateT1 -> {
                AcousticDefect(
                    code = "wolf_tone",
                    title = "狼音风险排查",
                    severity = DefectSeverity.MODERATE,
                    summary = "中度关注：T₁（${format1(t1.frequencyHz)} Hz）接近 ${t1Note.name(useFlats)}（${t1Frets}），当前 Q=${format1(t1.qFactor)} 尚属温和",
                    detail = "由于面板阻尼适中（Q < 35），一般不会爆发剧烈蜂鸣，但在高张力琴弦或极度干燥季节需留意 ${t1Note.name(useFlats)} 对应品位的延音均匀度。"
                )
            }

            else -> {
                val centsGap = abs(t1.centsOffset).roundToInt()
                AcousticDefect(
                    code = "wolf_tone",
                    title = "狼音风险排查",
                    severity = DefectSeverity.OK,
                    summary = "极低风险：T₁ 偏离最近半音 ${t1Note.name(useFlats)} 达 ${centsGap} 音分（安全错开）",
                    detail = "面板与背板主共振峰均避开了琴弦标准半音基频临界区（理想错开量为 40–50 音分），各品位能量释放平稳顺滑。"
                )
            }
        }
    }

    /**
     * 诊断 2：面背板反相相位相消排查（规范 §3.2）。
     */
    private fun diagnoseBackTopCoupling(
        t1: DetectedPeak?,
        b1: DetectedPeak?,
        diffHz: Double?,
        diffSemitones: Double?
    ): AcousticDefect {
        if (t1 == null || b1 == null || diffHz == null || diffSemitones == null) {
            return AcousticDefect("anti_phase", "面背板耦合状态", DefectSeverity.MODERATE, "缺少背板或面板主频数据", "请完整完成琴码中心与背板中心敲击测试。")
        }
        val absDiff = abs(diffHz)
        return when {
            absDiff < 12.0 -> AcousticDefect(
                code = "anti_phase",
                title = "面背板耦合状态",
                severity = DefectSeverity.SEVERE,
                summary = "反相相消高危：B₁ 与 T₁ 仅相差 ${format1(absDiff)} Hz（${format1(abs(diffSemitones))} 半音）",
                detail = "面背板主频距离小于 12 Hz（不足 1 个半音），两者容易同频反相争夺箱体内压，引发中低频能量塌陷与听感单薄空洞。"
            )

            diffHz < 0.0 -> AcousticDefect(
                code = "anti_phase",
                title = "面背板耦合状态",
                severity = DefectSeverity.MODERATE,
                summary = "背板主频低于面板：B₁ 比 T₁ 低 ${format1(absDiff)} Hz（${format1(diffSemitones)} 半音）",
                detail = "传统活性背板设计通常令背板高于面板 1.5–3 个半音；当前背板偏软/偏重，低音可能偏向厚重松散。"
            )

            diffHz in 15.0..38.0 -> AcousticDefect(
                code = "anti_phase",
                title = "面背板耦合状态",
                severity = DefectSeverity.OK,
                summary = "良好谐波增益：B₁ 高出 T₁ 达 +${format1(diffHz)} Hz（+${format1(diffSemitones)} 半音）",
                detail = "面背板频差稳稳落在理想的 15–35 Hz（约 1.5–3 个半音）黄金窗口内，背板对中低频形成健康的活性增益并有效避免相位抵消。"
            )

            diffHz > 38.0 -> AcousticDefect(
                code = "anti_phase",
                title = "面背板耦合状态",
                severity = DefectSeverity.OK,
                summary = "背板偏刚性反射：B₁ 高出 T₁ 达 +${format1(diffHz)} Hz（+${format1(diffSemitones)} 半音）",
                detail = "无反相相消风险；背板倾向刚性声反射设计，投射集中、清晰度高，但低频活性润色相对克制。"
            )

            else -> AcousticDefect(
                code = "anti_phase",
                title = "面背板耦合状态",
                severity = DefectSeverity.OK,
                summary = "耦合安全：B₁ 高出 T₁ 达 +${format1(diffHz)} Hz（+${format1(diffSemitones)} 半音）",
                detail = "面背板频差超过 12 Hz 安全下限，未发现明显反相抵消陷阱。"
            )
        }
    }

    /**
     * 诊断 3：70–90 Hz 琴颈一阶弯曲模态吸收谷 / 死点排查（规范 §3.2）。
     */
    private fun diagnoseNeckDeadSpot(bridge: PositionSpectrum): AcousticDefect {
        val leftRef = avgDbInRange(bridge, 60.0, 68.0)
        val rightRef = avgDbInRange(bridge, 92.0, 100.0)
        val trend = (leftRef + rightRef) * 0.5
        val start = bridge.localIndexForFrequency(70.0)
        val end = bridge.localIndexForFrequency(90.0)
        var minVal = Double.POSITIVE_INFINITY
        var minFreq = 80.0
        for (i in start..end) {
            val v = bridge.magnitudesDbfs[i].toDouble()
            if (v < minVal) {
                minVal = v
                minFreq = bridge.frequencyAt(i)
            }
        }
        val notchDepth = trend - minVal
        val aboveNoise = trend - bridge.noiseFloorDbfs

        return if (aboveNoise >= 12.0 && notchDepth >= 9.0) {
            AcousticDefect(
                code = "neck_dead_spot",
                title = "琴颈刚度与死点状态",
                severity = DefectSeverity.MODERATE,
                summary = "在 ${format1(minFreq)} Hz 处检测到 ${format1(notchDepth)} dB 低频吸收谷",
                detail = "70–90 Hz 频段存在较明显的凹陷，可能提示整琴一阶弯曲模态在个别把位吸收琴弦能量。可尝试在琴头夹上较重变调夹对比弹奏高把位延音变化以复核。"
            )
        } else {
            AcousticDefect(
                code = "neck_dead_spot",
                title = "琴颈刚度与死点状态",
                severity = DefectSeverity.OK,
                summary = "未检测到明显 70–90 Hz 弯曲模态吸收陷阱",
                detail = "超低频段包络过渡连续，琴颈与箱体衔接刚度表现正常，无显著能量泄漏迹象。"
            )
        }
    }

    /**
     * 五维量化评分模型（规范 §3.3）。
     */
    private fun scoreDimensions(
        bridge: PositionSpectrum,
        a0: DetectedPeak?,
        t1: DetectedPeak?,
        t12: DetectedPeak?,
        dipoleRatio: Double?,
        t1RefDb: Double
    ): List<DimensionScore> {
        // 1) 动态灵敏度 25%：T1 相对底噪的信噪比
        val t1Snr = (t1?.amplitudeDbfs ?: t1RefDb) - bridge.noiseFloorDbfs
        val sensitivityScore = interpolateScore(
            value = t1Snr,
            anchors = listOf(10.0 to 35.0, 20.0 to 58.0, 28.0 to 76.0, 35.0 to 87.0, 48.0 to 98.0)
        )
        val sensitivityBasis = "T₁ 峰值高出环境底噪 ${format1(t1Snr)} dB（优秀基准 ≥ 35 dB），" + when {
            t1Snr >= 35.0 -> "单极导纳充沛，轻触即起振。"
            t1Snr >= 24.0 -> "起振响应良好，动态余量适中。"
            else -> "面板驱动门槛偏高或敲击采集信噪比偏弱。"
        }

        // 2) 低频下潜与丰满度 20%：A0 频率 + SPL(A0)-SPL(T1)
        val a0Freq = a0?.frequencyHz ?: 108.0
        val a0Rel = a0?.relativeDb ?: -10.0
        val a0FreqScore = when {
            a0Freq in 95.0..105.0 -> 95.0
            a0Freq in 90.0..95.0 -> 88.0
            a0Freq in 105.0..112.0 -> 82.0
            a0Freq < 90.0 -> 78.0
            a0Freq <= 118.0 -> 66.0
            else -> 50.0
        }
        val a0BalScore: Double = when {
            a0Rel in -6.0..-2.0 -> 95.0
            a0Rel > -2.0 -> (92.0 - (a0Rel + 2.0) * 3.0).coerceIn(68.0, 92.0) // 气腔偏肥略扣
            a0Rel in -10.0..-6.0 -> interpolateScore(a0Rel, listOf(-10.0 to 72.0, -6.0 to 95.0)).toDouble()
            else -> interpolateScore(a0Rel, listOf(-20.0 to 42.0, -12.0 to 58.0, -10.0 to 72.0)).toDouble()
        }
        val bassScore = ((a0FreqScore * 0.55 + a0BalScore * 0.45).roundToInt()).coerceIn(20, 100)
        val bassBasis = "A₀ 位于 ${format1(a0Freq)} Hz，相对 T₁ 电平 ${formatSigned1(a0Rel)} dB（理想 95–105 Hz、-3 至 -6 dB）。"

        // 3) 延音稳定性 20%：Q_T1 与阻尼分布
        val qT1 = t1?.qFactor ?: 18.0
        val sustainScore = when {
            qT1 in 20.0..32.0 -> 94
            qT1 in 15.0..20.0 -> interpolateScore(qT1, listOf(15.0 to 78.0, 20.0 to 94.0))
            qT1 in 32.0..42.0 -> interpolateScore(qT1, listOf(32.0 to 94.0, 42.0 to 76.0))
            qT1 > 42.0 -> interpolateScore(qT1, listOf(42.0 to 76.0, 55.0 to 52.0, 75.0 to 38.0))
            else -> interpolateScore(qT1, listOf(5.0 to 40.0, 10.0 to 56.0, 15.0 to 78.0))
        }
        val sustainBasis = "T₁ 品质因数 Q=${format1(qT1)}（阻尼比 ζ=${String.format("%.3f", t1?.dampingRatio ?: 0.025)}），" + when {
            qT1 in 20.0..32.0 -> "落入理想区间，延音顺滑均匀。"
            qT1 > 42.0 -> "共振偏尖锐，需留意个别音符能量泄放过快。"
            qT1 < 12.0 -> "阻尼偏大，尾音收敛偏快。"
            else -> "延音衰减控制较平衡。"
        }

        // 4) 谐波协同与结实度 20%：T1,2 / T1 趋近 2.0
        val ratio = dipoleRatio ?: 1.85
        val ratioErr = abs(ratio - 2.0)
        val couplingScore = interpolateScore(
            value = ratioErr,
            anchors = listOf(0.0 to 98.0, 0.05 to 91.0, 0.10 to 82.0, 0.18 to 68.0, 0.30 to 48.0)
        )
        val couplingBasis = if (t12 != null && dipoleRatio != null) {
            "纵偶极 T₁,₂=${format1(t12.frequencyHz)} Hz，与 T₁ 比值 ${String.format("%.2f", ratio)}（基准 2.00 ± 5% 八度协同）。"
        } else {
            "偶极子响应较平缓，中频冲击感温和。"
        }

        // 5) 中高频延展 15%：1–5 kHz 相对能量 + 高阶峰密度
        val hfAvgDb = avgDbInRange(bridge, 1000.0, 5000.0)
        val hfDrop = t1RefDb - hfAvgDb
        val hfPeaks = findProminentPeaks(bridge, 1000.0, 5000.0, minProminenceDb = 3.0).size
        val hfEnergyScore = interpolateScore(
            value = hfDrop,
            anchors = listOf(12.0 to 96.0, 18.0 to 88.0, 24.0 to 74.0, 32.0 to 54.0, 42.0 to 38.0)
        )
        val hfDensityBonus = (hfPeaks * 0.7).coerceAtMost(8.0)
        val airScore = (hfEnergyScore + hfDensityBonus - 4.0).roundToInt().coerceIn(25, 100)
        val airBasis = "1–5 kHz 平均能量相对主频衰减 ${format1(hfDrop)} dB，检测到 $hfPeaks 个高阶离散泛音峰。"

        return listOf(
            DimensionScore("sensitivity", "动态灵敏度", 25, sensitivityScore, sensitivityBasis),
            DimensionScore("bass", "低频下潜与丰满度", 20, bassScore, bassBasis),
            DimensionScore("sustain", "延音稳定性", 20, sustainScore, sustainBasis),
            DimensionScore("coupling", "谐波协同与结实度", 20, couplingScore, couplingBasis),
            DimensionScore("air", "中高频延展与空气感", 15, airScore, airBasis)
        )
    }

    /**
     * 四大主流曲风归一化欧氏距离匹配引擎（规范 §4.2）。
     */
    private fun matchPlayingStyles(
        a0Hz: Double?,
        t1Hz: Double?,
        backTopDiffHz: Double?,
        t21RelDb: Double?,
        dipoleRatio: Double?,
        rollOffHz: Double,
        qT1: Double?,
        attackMs: Double
    ): List<StyleMatch> {
        val a0 = a0Hz ?: 100.0
        val t1 = t1Hz ?: 186.0
        val diff = backTopDiffHz ?: 24.0
        val t21Rel = t21RelDb ?: -10.0
        val ratio = dipoleRatio ?: 1.92
        val q = qT1 ?: 24.0

        data class StyleProfile(
            val style: PlayingStyle,
            val a0Target: Double, val a0Tol: Double,
            val t1Target: Double, val t1Tol: Double,
            val diffTarget: Double, val diffTol: Double,
            val t21Target: Double, val t21Tol: Double,
            val ratioTarget: Double, val ratioTol: Double,
            val rollOffTarget: Double, val rollOffTol: Double,
            val extraPenalty: () -> Double
        )

        val profiles = listOf(
            StyleProfile(
                style = PlayingStyle.FINGERSTYLE,
                a0Target = 99.0, a0Tol = 6.0,
                t1Target = 178.0, t1Tol = 11.0,
                diffTarget = 25.0, diffTol = 10.0,
                t21Target = -7.0, t21Tol = 5.0,
                ratioTarget = 1.90, ratioTol = 0.12,
                rollOffTarget = 5000.0, rollOffTol = 1500.0,
                extraPenalty = { if (q < 14.0) 0.6 else 0.0 }
            ),
            StyleProfile(
                style = PlayingStyle.STRUMMING,
                a0Target = 106.0, a0Tol = 7.0,
                t1Target = 198.0, t1Tol = 11.0,
                diffTarget = 35.0, diffTol = 12.0,
                t21Target = -13.0, t21Tol = 6.0,
                ratioTarget = 2.00, ratioTol = 0.08,
                rollOffTarget = 4000.0, rollOffTol = 1400.0,
                extraPenalty = { if (t1 < 176.0) 0.5 else 0.0 }
            ),
            StyleProfile(
                style = PlayingStyle.WARM_JAZZ,
                a0Target = 93.0, a0Tol = 6.0,
                t1Target = 181.0, t1Tol = 10.0,
                diffTarget = 20.0, diffTol = 10.0,
                t21Target = -10.5, t21Tol = 5.5,
                ratioTarget = 1.85, ratioTol = 0.12,
                rollOffTarget = 2500.0, rollOffTol = 1100.0,
                extraPenalty = { if (q > 34.0) 0.5 else 0.0 }
            ),
            StyleProfile(
                style = PlayingStyle.PERCUSSIVE,
                a0Target = 104.0, a0Tol = 7.0,
                t1Target = 212.0, t1Tol = 12.0,
                diffTarget = 28.0, diffTol = 12.0,
                t21Target = -15.0, t21Tol = 6.0,
                ratioTarget = 1.97, ratioTol = 0.12,
                rollOffTarget = 5500.0, rollOffTol = 1600.0,
                extraPenalty = {
                    val attackPen = if (attackMs > 38.0) 0.45 else 0.0
                    val qPen = if (q > 32.0) 0.45 else 0.0
                    attackPen + qPen
                }
            )
        )

        return profiles.map { p ->
            val d1 = (a0 - p.a0Target) / p.a0Tol
            val d2 = (t1 - p.t1Target) / p.t1Tol
            val d3 = (diff - p.diffTarget) / p.diffTol
            val d4 = (t21Rel - p.t21Target) / p.t21Tol
            val d5 = (ratio - p.ratioTarget) / p.ratioTol
            val d6 = (rollOffHz - p.rollOffTarget) / p.rollOffTol
            val rmsDist = sqrt((d1 * d1 + d2 * d2 * 1.4 + d3 * d3 * 0.7 + d4 * d4 + d5 * d5 * 1.1 + d6 * d6 * 0.8) / 6.0) + p.extraPenalty()
            val matchPct = (100.0 * exp(-0.36 * rmsDist * rmsDist)).roundToInt().coerceIn(32, 98)
            StyleMatch(
                style = p.style,
                matchPercent = matchPct,
                reason = styleExplanation(p.style, a0, t1, t21Rel, ratio, rollOffHz)
            )
        }.sortedByDescending { it.matchPercent }
    }

    private fun styleExplanation(
        style: PlayingStyle,
        a0: Double,
        t1: Double,
        t21Rel: Double,
        ratio: Double,
        rollOffHz: Double
    ): String = when (style) {
        PlayingStyle.FINGERSTYLE ->
            "面板主频 T₁=${format1(t1)} Hz、气腔 A₀=${format1(a0)} Hz，横向偶极 T₂,₁ 相对幅值 ${formatSigned1(t21Rel)} dB，" +
                if (t1 <= 188.0) "轻触起振灵敏且近场声场开阔，契合多声部独奏分离度要求。"
                else "具备较清晰的单音轮廓，拨弦颗粒度明确。"

        PlayingStyle.STRUMMING ->
            "T₁=${format1(t1)} Hz、纵偶极协同比 ${String.format("%.2f", ratio)}，" +
                if (t1 >= 190.0) "面板抗弯刚度与动态余量充足，拨片扫弦低频收敛扎实、不易掩盖人声。"
                else "中频协同带来一定扫弦轮廓，但极大动态扫弦需注意控制低音力度。"

        PlayingStyle.WARM_JAZZ ->
            "A₀ 下潜至 ${format1(a0)} Hz，高频转折点约 ${format1(rollOffHz / 1000.0)} kHz，" +
                if (rollOffHz <= 3300.0) "高频衰减温润克制，木质基频厚实温暖，无刺耳金属脆声。"
                else "中低频底色温厚，但高频仍保留一定泛音明亮度。"

        PlayingStyle.PERCUSSIVE ->
            "T₁=${format1(t1)} Hz、高频延展至 ${format1(rollOffHz / 1000.0)} kHz，" +
                if (t1 >= 200.0) "高刚度面板起振迅猛、切音干脆，适合高速点弦与敲板打击技法。"
                else "面板偏向长延音共振，极高速切音时尾韵收束相对温和。"
    }

    private fun buildNotRecommendedHint(
        matches: List<StyleMatch>,
        t1: DetectedPeak?,
        a0: DetectedPeak?,
        rollOffHz: Double
    ): String {
        val worst = matches.lastOrNull() ?: return ""
        val t1Freq = t1?.frequencyHz ?: 185.0
        val specific = when (worst.style) {
            PlayingStyle.STRUMMING -> "面板主频偏低（${format1(t1Freq)} Hz）且灵敏度较高，极大力道重型扫弦时低频易产生过载浑浊。"
            PlayingStyle.FINGERSTYLE -> "面板主频偏高（${format1(t1Freq)} Hz）且刚度偏硬，极轻柔指弹下微弱动态展开相对受限。"
            PlayingStyle.WARM_JAZZ -> "中高频泛音在 ${(rollOffHz / 1000.0).let { format1(it) }} kHz 仍有较高能量，音色偏清亮现代而非暗暖老派爵士色调。"
            PlayingStyle.PERCUSSIVE -> "面板延音偏向饱满开放（A₀=${format1(a0?.frequencyHz ?: 100.0)} Hz），做高速密集弗拉门戈切音时干脆度略逊于高刚度琴。"
        }
        return "${worst.style.label}（契合度 ${worst.matchPercent}%）：$specific"
    }

    private fun buildStringTensionAdvice(
        t1: DetectedPeak?,
        bodyShape: GuitarBodyShape,
        topStyle: PlayingStyle?
    ): String {
        val f = t1?.frequencyHz ?: 186.0
        return when {
            f < 180.0 -> "面板偏轻软高灵敏（T₁=${format1(f)} Hz），推荐使用 Light (.012–.053) 或 Custom Light (.011–.052) 琴弦，保护面板拱度并避免扫弦过载。"
            f > 198.0 -> "面板刚度充沛（T₁=${format1(f)} Hz，${bodyShape.label}），可驾驭 Medium (.013–.056) 或 Bluegrass 琴弦，以充分驱动面板释放中低频能量。"
            topStyle == PlayingStyle.WARM_JAZZ -> "面板刚度均衡（T₁=${format1(f)} Hz），推荐磷青铜（Phosphor Bronze）.012 琴弦或丝钢弦，进一步突显温暖木质色调。"
            else -> "面板刚度均衡（T₁=${format1(f)} Hz），标准 Light (.012–.053) 磷青铜或 80/20 黄铜琴弦即可在灵敏度与动态余量间取得最佳平衡。"
        }
    }

    private fun buildVoicingAdvice(
        defects: List<AcousticDefect>,
        t1: DetectedPeak?,
        b1: DetectedPeak?,
        decoupleShiftHz: Double?,
        decoupleSkipped: Boolean
    ): String {
        val tips = mutableListOf<String>()
        if (defects.any { it.code == "wolf_tone" && it.severity == DefectSeverity.SEVERE }) {
            tips.add("检测到高危狼音重合：可尝试更换不同材质/重量的固弦锥（如黑檀/黄铜）微调琴码等效质量，或联系制琴师在背板/面板内侧微调配重，将主峰偏移至两半音中央缝隙。")
        }
        if (defects.any { it.code == "anti_phase" && it.severity == DefectSeverity.SEVERE }) {
            tips.add("面背板频差过近：演奏时可通过调整抱琴贴合背板的接触面积微增背板阻尼，或在专业维护时校验背板音梁调音。")
        }
        if (!decoupleSkipped && decoupleShiftHz != null) {
            tips.add("封音孔解耦校验：空气腔将面板固有基频推动偏移约 ${formatSigned1(decoupleShiftHz)} Hz，表明气腔-面板声学耦合通道工作正常。")
        } else if (decoupleSkipped) {
            tips.add("本次跳过了第 5 步「封音孔解耦」，后续若需精准量化面板裸刚度与空气耦合深度，可用音孔塞/厚纸板复测完整 5 点。")
        }
        if (tips.isEmpty() && t1 != null && b1 != null) {
            tips.add("核心模态拓扑均衡（T₁=${format1(t1.frequencyHz)} Hz，B₁=${format1(b1.frequencyHz)} Hz），建议保持 45%–55% RH 恒湿存放，并在换弦或开声周期后复测追踪频点漂移。")
        }
        return tips.joinToString(" ")
    }

    private fun estimateHighFreqRollOffHz(bridge: PositionSpectrum, refDb: Double): Double {
        val bands = listOf(
            1500.0 to 2000.0,
            2000.0 to 2800.0,
            2800.0 to 3800.0,
            3800.0 to 5000.0,
            5000.0 to 6000.0
        )
        val baseline = avgDbInRange(bridge, 600.0, 1200.0).coerceAtLeast(refDb - 26.0)
        for ((lo, hi) in bands) {
            val avg = avgDbInRange(bridge, lo, hi)
            if (baseline - avg >= 12.0) {
                return (lo + hi) * 0.5
            }
        }
        return 5500.0
    }

    private fun avgDbInRange(spectrum: PositionSpectrum, minHz: Double, maxHz: Double): Double {
        if (spectrum.magnitudesDbfs.isEmpty()) return -100.0
        val start = spectrum.localIndexForFrequency(minHz)
        val end = spectrum.localIndexForFrequency(maxHz)
        if (end < start) return -100.0
        var sum = 0.0
        for (i in start..end) sum += spectrum.magnitudesDbfs[i]
        return sum / (end - start + 1)
    }

    private fun interpolateScore(value: Double, anchors: List<Pair<Double, Double>>): Int {
        if (value <= anchors.first().first) return anchors.first().second.roundToInt()
        if (value >= anchors.last().first) return anchors.last().second.roundToInt()
        for (i in 0 until anchors.lastIndex) {
            val (x0, y0) = anchors[i]
            val (x1, y1) = anchors[i + 1]
            if (value in x0..x1) {
                val t = (value - x0) / (x1 - x0)
                return (y0 + t * (y1 - y0)).roundToInt().coerceIn(0, 100)
            }
        }
        return 70
    }

    /** 将平均律音高映射到标准调弦（E2 A2 D3 G3 B3 E4）上的具体弦号与品位（优先展示空弦与低把位，最多列出 2 处）。 */
    fun describeGuitarFrets(note: Note): String {
        val strings = TuningLibrary.STANDARD.strings // index 0 = 6弦 E2 .. index 5 = 1弦 E4
        val matches = mutableListOf<Pair<Int, String>>()
        for (idx in strings.indices) {
            val fret = note.midi - strings[idx].midi
            if (fret in 0..12) {
                val stringNo = 6 - idx
                val text = if (fret == 0) "${stringNo}弦空弦" else "${stringNo}弦${fret}品"
                matches.add(fret to text)
            }
        }
        return if (matches.isEmpty()) "高把位"
        else matches.sortedBy { it.first }.take(2).joinToString(" / ") { it.second }
    }

    private fun format1(v: Double): String = String.format("%.1f", v)
    private fun formatSigned1(v: Double): String = String.format("%+.1f", v)
}
