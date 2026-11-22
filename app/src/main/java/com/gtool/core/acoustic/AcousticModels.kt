package com.gtool.core.acoustic

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * 标准化五点拓扑敲击点位（遵循原声吉他敲击 FFT 声学分析规范 §2.2）。
 */
@Serializable
enum class TapPosition(
    val stepIndex: Int,
    val code: String,
    val title: String,
    val shortTitle: String,
    val targetModesLabel: String,
    val instruction: String,
    val micPlacement: String,
    val optional: Boolean = false
) {
    BRIDGE(
        stepIndex = 1,
        code = "Spectrum-Bridge",
        title = "琴码中心",
        shortTitle = "琴码中心",
        targetModesLabel = "A₀ / T₁",
        instruction = "轻敲琴码中心基座（低音 E 弦与 A 弦销钉之间），激发整琴基准单极子与气腔耦合响应。",
        micPlacement = "麦克风正对琴码中线上方 25 cm，拾音轴垂直于面板，琴身悬空。"
    ),
    DIPOLE_L(
        stepIndex = 2,
        code = "Spectrum-Dipole-L",
        title = "琴码侧翼",
        shortTitle = "琴码侧翼",
        targetModesLabel = "T₂,₁ 横偶极",
        instruction = "轻敲琴码低音侧或高音侧边缘翼部末端，激发左右反相振动的横向偶极子模态。",
        micPlacement = "麦克风微移至敲击同侧上方约 25 cm，保持垂直面板。"
    ),
    DIPOLE_V(
        stepIndex = 3,
        code = "Spectrum-Dipole-V",
        title = "下腹中线",
        shortTitle = "下腹中线",
        targetModesLabel = "T₁,₂ 纵偶极",
        instruction = "轻敲面板下腹部中线（琴码正下方 4–6 cm 处），避开琴码压制以激发纵向偶极子。",
        micPlacement = "麦克风正对面板下腹部上方 25 cm，琴身保持悬空自由边界。"
    ),
    BACK(
        stepIndex = 4,
        code = "Spectrum-Back",
        title = "背板中心",
        shortTitle = "背板中心",
        targetModesLabel = "B₁ 背板主频",
        instruction = "翻转吉他使背板面向麦克风，轻敲背板下肚几何中心，提取背板独立主单极子模态。",
        micPlacement = "手拎琴头悬空或面板垫软海绵，麦克风正对背板中心 25 cm。"
    ),
    DECOUPLE(
        stepIndex = 5,
        code = "Spectrum-Decouple",
        title = "封音孔解耦",
        shortTitle = "封音孔",
        targetModesLabel = "T₁ 裸基频校验",
        instruction = "用厚纸板或软木音孔塞严密封闭音孔后，重复敲击琴码中心，校验面板未耦合固有基频。",
        micPlacement = "音孔密闭封堵，麦克风仍正对琴码上方 25 cm（手头无封堵物可跳过）。",
        optional = true
    );

    companion object {
        val ordered: List<TapPosition> = entries.sortedBy { it.stepIndex }
    }
}

/**
 * 常见钢弦原声吉他箱体形制。
 */
@Serializable
enum class GuitarBodyShape(val label: String) {
    DREADNOUGHT("Dreadnought (D桶)"),
    OM("OM / 000"),
    GA("GA / Grand Auditorium"),
    SMALL_JUMBO("Small Jumbo (SJ)"),
    PARLOR("Parlor / 00"),
    CONCERT("Grand Concert"),
    JUMBO("Jumbo"),
    OTHER("其他形制")
}

/**
 * 原声吉他核心共振模态标识。
 */
@Serializable
enum class ModalId(
    val symbol: String,
    val asciiCode: String,
    val description: String,
    val minHz: Double,
    val maxHz: Double,
    val primaryPosition: TapPosition
) {
    A0("A₀", "A0", "空气腔体准亥姆霍兹共振", 80.0, 135.0, TapPosition.BRIDGE),
    T1("T₁", "T1", "面板核心单极子活塞呼吸模态", 145.0, 235.0, TapPosition.BRIDGE),
    B1("B₁", "B1", "背板核心单极子共振模态", 175.0, 285.0, TapPosition.BACK),
    T21("T₂,₁", "T2,1", "面板横向偶极子模态（左右反相）", 245.0, 385.0, TapPosition.DIPOLE_L),
    T12("T₁,₂", "T1,2", "面板纵向偶极子模态（上下反相）", 315.0, 470.0, TapPosition.DIPOLE_V),
    T31("T₃,₁", "T3,1", "面板横向三极子模态", 395.0, 520.0, TapPosition.BRIDGE),
    T1_UNCOUPLED("T₁(裸)", "T1-Uncoupled", "封音孔后面板固有结构基频", 140.0, 235.0, TapPosition.DECOUPLE);

    companion object {
        /** 报告核心 5 模态（按规范表格顺序）。 */
        val coreModals: List<ModalId> = listOf(A0, T1, B1, T21, T12)
    }
}

/**
 * 单次敲击的时域/频域质量指标（保留供原始数据查询）。
 */
@Serializable
data class TapSampleMetrics(
    val tapIndex: Int,
    val peakDbfs: Double,
    val snrDb: Double,
    val attackTimeMs: Double,
    val decayTauMs: Double,
    val coherenceToMean: Double,
    val accepted: Boolean = true,
    val rejectReason: String? = null
)

/**
 * 单个敲击点位经多次功率谱平均后的高分辨率 FFT 原始数据。
 * 默认保存 30 Hz – 6000 Hz 全分辨率频点（48 kHz / 65536 点下约 0.7324 Hz/bin）。
 */
@Serializable
data class PositionSpectrum(
    val position: TapPosition,
    val sampleRateHz: Int = 48_000,
    val fftSize: Int = 65_536,
    val noiseFloorDbfs: Double = -75.0,
    val coherenceScore: Double = 1.0,
    val qualityOverride: Boolean = false,
    val taps: List<TapSampleMetrics> = emptyList(),
    val binStartIndex: Int = 41,
    val freqStepHz: Double = 48_000.0 / 65_536.0,
    val magnitudesDbfs: List<Float> = emptyList(),
    val avgAttackTimeMs: Double = 35.0,
    val avgDecayTauMs: Double = 90.0
) {
    val binCount: Int get() = magnitudesDbfs.size

    val minFrequencyHz: Double get() = binStartIndex * freqStepHz

    val maxFrequencyHz: Double
        get() = if (magnitudesDbfs.isEmpty()) minFrequencyHz
        else (binStartIndex + magnitudesDbfs.lastIndex) * freqStepHz

    fun frequencyAt(localIndex: Int): Double = (binStartIndex + localIndex) * freqStepHz

    fun localIndexForFrequency(hz: Double): Int {
        if (magnitudesDbfs.isEmpty()) return 0
        val idx = ((hz / freqStepHz) - binStartIndex).roundToInt()
        return idx.coerceIn(0, magnitudesDbfs.lastIndex)
    }

    /** 60–5000 Hz 范围内的最高幅度（dBFS），用于相对 0 dB 归一化。 */
    fun peakMagnitudeDbfs(minHz: Double = 60.0, maxHz: Double = 5000.0): Double {
        if (magnitudesDbfs.isEmpty()) return -100.0
        val start = localIndexForFrequency(minHz)
        val end = localIndexForFrequency(maxHz)
        var maxVal = Float.NEGATIVE_INFINITY
        for (i in start..end) {
            val v = magnitudesDbfs[i]
            if (v > maxVal) maxVal = v
        }
        return if (maxVal.isFinite()) maxVal.toDouble() else -100.0
    }

    /** 任意频率处的线性插值幅度（dBFS）。 */
    fun magnitudeAtFrequency(hz: Double): Double {
        if (magnitudesDbfs.isEmpty()) return -120.0
        val exact = (hz / freqStepHz) - binStartIndex
        if (exact <= 0.0) return magnitudesDbfs.first().toDouble()
        if (exact >= magnitudesDbfs.lastIndex) return magnitudesDbfs.last().toDouble()
        val lo = exact.toInt()
        val frac = exact - lo
        return magnitudesDbfs[lo] * (1.0 - frac) + magnitudesDbfs[lo + 1] * frac
    }
}

/**
 * 识别到的单个共振模态参数。
 */
@Serializable
data class DetectedPeak(
    val modalId: ModalId,
    val frequencyHz: Double,
    val amplitudeDbfs: Double,
    val relativeDb: Double,
    val qFactor: Double,
    val decayQ: Double,
    val dampingRatio: Double,
    val tauMs: Double,
    val nearestNoteName: String,
    val centsOffset: Double,
    val sourcePosition: TapPosition,
    val isManualOverride: Boolean = false
)

@Serializable
enum class DefectSeverity(val label: String) {
    OK("良好 / 极低风险"),
    MODERATE("中度关注"),
    SEVERE("高危 / 明显缺陷")
}

@Serializable
data class AcousticDefect(
    val code: String,
    val title: String,
    val severity: DefectSeverity,
    val summary: String,
    val detail: String
)

@Serializable
data class DimensionScore(
    val key: String,
    val label: String,
    val weightPercent: Int,
    val score: Int,
    val basis: String
)

@Serializable
enum class PlayingStyle(val label: String, val englishName: String) {
    FINGERSTYLE("现代指弹独奏", "Fingerstyle & Solo"),
    STRUMMING("强力扫弦 / 弹唱伴奏", "Strumming & Rhythm"),
    WARM_JAZZ("爵士 / 柔和抒情", "Warm Jazz & Mellow"),
    PERCUSSIVE("快速打击 / 弗拉门戈", "Fast Percussive & Flamenco")
}

@Serializable
data class StyleMatch(
    val style: PlayingStyle,
    val matchPercent: Int,
    val reason: String
)

/**
 * 完整声学体检分析结果（可由原始 FFT 数据 + 手动共振峰覆写确定性重算）。
 */
@Serializable
data class AcousticReport(
    val peaks: Map<ModalId, DetectedPeak> = emptyMap(),
    val backTopDiffHz: Double? = null,
    val backTopDiffSemitones: Double? = null,
    val dipoleRatio: Double? = null,
    val decoupleShiftHz: Double? = null,
    val a0DecoupleDropDb: Double? = null,
    val highFreqRollOffHz: Double = 3500.0,
    val defects: List<AcousticDefect> = emptyList(),
    val dimensionScores: List<DimensionScore> = emptyList(),
    val overallScore: Int = 0,
    val qualityGrade: String = "基础练习级",
    val styleMatches: List<StyleMatch> = emptyList(),
    val notRecommendedHint: String = "",
    val stringTensionAdvice: String = "",
    val voicingAdvice: String = "",
    val decoupleSkipped: Boolean = false,
    val hasQualityOverride: Boolean = false,
    val minCoherence: Double = 0.0
)

/**
 * 单次完整的吉他声学分析记录（落盘至 filesDir/acoustic/records/{id}.json）。
 */
@Serializable
data class AcousticRecord(
    val id: String,
    val guitarName: String,
    val bodyShape: GuitarBodyShape = GuitarBodyShape.DREADNOUGHT,
    val notes: String = "",
    val inputDeviceName: String = "手机内置麦克风",
    val a4Hz: Double = 440.0,
    val createdAtEpochMs: Long,
    val spectra: Map<TapPosition, PositionSpectrum>,
    val manualPeakOverrides: Map<ModalId, Double> = emptyMap(),
    val report: AcousticReport
) {
    fun toSummary(): AcousticRecordSummary = AcousticRecordSummary(
        id = id,
        guitarName = guitarName,
        bodyShape = bodyShape,
        notes = notes,
        inputDeviceName = inputDeviceName,
        createdAtEpochMs = createdAtEpochMs,
        overallScore = report.overallScore,
        qualityGrade = report.qualityGrade,
        a0Hz = report.peaks[ModalId.A0]?.frequencyHz,
        t1Hz = report.peaks[ModalId.T1]?.frequencyHz,
        b1Hz = report.peaks[ModalId.B1]?.frequencyHz,
        positions = spectra.keys.sortedBy { it.stepIndex },
        topStyleLabel = report.styleMatches.firstOrNull()?.style?.label ?: "-"
    )
}

/**
 * 档案主页索引条（落盘至 filesDir/acoustic/index.json，保证列表秒开）。
 */
@Serializable
data class AcousticRecordSummary(
    val id: String,
    val guitarName: String,
    val bodyShape: GuitarBodyShape = GuitarBodyShape.DREADNOUGHT,
    val notes: String = "",
    val inputDeviceName: String = "手机内置麦克风",
    val createdAtEpochMs: Long,
    val overallScore: Int,
    val qualityGrade: String,
    val a0Hz: Double? = null,
    val t1Hz: Double? = null,
    val b1Hz: Double? = null,
    val positions: List<TapPosition> = emptyList(),
    val topStyleLabel: String = ""
)
