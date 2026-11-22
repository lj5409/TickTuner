package com.gtool.core.acoustic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 声学诊断报告 Markdown 生成器（严格遵循原声吉他敲击 FFT 声学分析规范 §5）
 * 与原始 FFT 数值 CSV 生成器（纯 Kotlin，可独立 JVM 单测）。
 */
object AcousticReportFormatter {

    fun toMarkdown(record: AcousticRecord): String {
        val report = record.report
        val dateStr = formatDateTime(record.createdAtEpochMs)
        val sb = StringBuilder(4096)

        sb.appendLine("# 原声吉他声振模态与频响特性诊断报告")
        sb.appendLine()
        sb.appendLine("## 一、 乐器档案与测量工况")
        sb.appendLine("* **乐器型号/名称**：${record.guitarName}")
        sb.appendLine("* **箱体形制**：${record.bodyShape.label}")
        sb.appendLine("* **检测时间**：$dateStr")
        sb.appendLine("* **音频输入设备**：${record.inputDeviceName}")
        if (record.notes.isNotBlank()) {
            sb.appendLine("* **测试备注**：${record.notes}")
        }
        val cohText = String.format(Locale.US, "%.2f", report.minCoherence)
        val extraFlags = buildList {
            if (report.decoupleSkipped) add("已跳过第 5 步封音孔解耦")
            if (report.hasQualityOverride) add("含嘈杂环境人工放宽确认点位")
        }.joinToString("；")
        val condSuffix = if (extraFlags.isNotEmpty()) "（$extraFlags）" else ""
        sb.appendLine("* **测试环境标定**：悬空自由边界，麦克风轴向距离 25 cm，48 kHz / 65536 点 FFT（0.73 Hz 分辨率 + 对数抛物线插值），多谱最低一致性 γ² = $cohText$condSuffix")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        sb.appendLine("## 二、 核心共振模态实测数据表")
        sb.appendLine()
        sb.appendLine("| 模态标识 | 物理定义与振型特征 | 测量频率 (Hz) | 品质因数 (Q值) | 相对声压 (dB) | 与最近半音音高偏差 (Cents) | 来源 |")
        sb.appendLine("| :--- | :--- | :--- | :--- | :--- | :--- | :--- |")
        val modalOrder = listOf(
            ModalId.A0,
            ModalId.T1,
            ModalId.B1,
            ModalId.T21,
            ModalId.T12,
            ModalId.T1_UNCOUPLED
        )
        for (id in modalOrder) {
            val peak = report.peaks[id] ?: continue
            val centsCell = if (id == ModalId.A0 || id == ModalId.T1 || id == ModalId.B1 || id == ModalId.T1_UNCOUPLED) {
                "${peak.nearestNoteName} (${String.format(Locale.US, "%+.0f", peak.centsOffset)}¢)"
            } else {
                "-"
            }
            val srcTag = if (peak.isManualOverride) "${peak.sourcePosition.shortTitle} (手动微调)" else peak.sourcePosition.shortTitle
            sb.appendLine(
                "| **${id.symbol}** | ${id.description} | " +
                    "${f1(peak.frequencyHz)} | ${f1(peak.qFactor)} | ${fs1(peak.relativeDb)} | $centsCell | $srcTag |"
            )
        }
        sb.appendLine()
        sb.appendLine("> **关键力学拓扑特征**：")
        val diffText = if (report.backTopDiffHz != null && report.backTopDiffSemitones != null) {
            "面背板主频音程差为 **${fs1(report.backTopDiffHz)} Hz**（相当于 **${fs1(report.backTopDiffSemitones)}** 个半音）"
        } else {
            "面背板频差未测"
        }
        val ratioText = if (report.dipoleRatio != null) {
            val closeness = if (abs(report.dipoleRatio - 2.0) <= 0.08) "高度接近" else "偏离"
            "纵偶极与面板主频比值为 **${String.format(Locale.US, "%.2f", report.dipoleRatio)}**（${closeness} 1:2 八度谐波比）"
        } else {
            "纵偶极比值未测"
        }
        val decoupleText = if (report.decoupleShiftHz != null && report.a0DecoupleDropDb != null) {
            "；封音孔解耦校验显示空气腔推动面板频移 **${fs1(report.decoupleShiftHz)} Hz**（A₀ 峰衰减 ${f1(report.a0DecoupleDropDb)} dB）"
        } else ""
        sb.appendLine("> $diffText；$ratioText$decoupleText。")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        sb.appendLine("## 三、 声学缺陷与物理稳定性诊断")
        for (defect in report.defects) {
            sb.appendLine("* **${defect.title}（${defect.severity.label}）**：${defect.summary}。${defect.detail}")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        sb.appendLine("## 四、 音质综合客观评级")
        sb.appendLine()
        sb.appendLine("| 评价维度 | 权重 | 得分 (1-100) | 物理判定依据与实测表现 |")
        sb.appendLine("| :--- | :--- | :--- | :--- |")
        for (dim in report.dimensionScores) {
            sb.appendLine("| **${dim.label}** | ${dim.weightPercent}% | ${dim.score} | ${dim.basis} |")
        }
        sb.appendLine("| **综合声学品质指数** | **100%** | **${report.overallScore}** | **评级：${report.qualityGrade}** |")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        sb.appendLine("## 五、 演奏风格与曲风适配建议")
        val top = report.styleMatches.firstOrNull()
        val second = report.styleMatches.getOrNull(1)
        if (top != null) {
            sb.appendLine("* **最佳适配流派（Top Match）**：**${top.style.label} (${top.style.englishName})**（匹配度：**${top.matchPercent}%**）")
            sb.appendLine("  * **适配机理**：${top.reason}")
        }
        if (second != null) {
            sb.appendLine("* **次要适配流派**：**${second.style.label} (${second.style.englishName})**（匹配度：**${second.matchPercent}%**）")
            sb.appendLine("  * **说明**：${second.reason}")
        }
        if (report.notRecommendedHint.isNotBlank()) {
            sb.appendLine("* **不推荐流派及局限提示**：${report.notRecommendedHint}")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()

        sb.appendLine("## 六、 针对性微调与保养建议 (Luthier Voicing Guide)")
        sb.appendLine("* **琴弦张力匹配建议**：${report.stringTensionAdvice}")
        sb.appendLine("* **物理结构微调方向**：${report.voicingAdvice}")
        sb.appendLine()
        sb.appendLine("*由 TickTuner 离线吉他声学分析引擎生成（A4 = ${f1(record.a4Hz)} Hz）。*")

        return sb.toString()
    }

    /**
     * 导出所有已测点位的原始 FFT 离散谱数据 CSV（包含各点位 dBFS 与相对 0 dB）。
     */
    fun toRawCsv(record: AcousticRecord, onlyPosition: TapPosition? = null): String {
        val positions = if (onlyPosition != null) {
            listOfNotNull(record.spectra[onlyPosition]?.let { onlyPosition to it })
        } else {
            TapPosition.ordered.mapNotNull { pos -> record.spectra[pos]?.let { pos to it } }
        }
        if (positions.isEmpty()) return ""

        val firstSpec = positions.first().second
        val sb = StringBuilder(positions.size * firstSpec.binCount * 16)
        sb.appendLine("# TickTuner Acoustic Raw FFT Data")
        sb.appendLine("# Guitar: ${record.guitarName}")
        sb.appendLine("# BodyShape: ${record.bodyShape.label}")
        sb.appendLine("# Timestamp: ${formatDateTime(record.createdAtEpochMs)}")
        sb.appendLine("# InputDevice: ${record.inputDeviceName}")
        sb.appendLine("# SampleRateHz: ${firstSpec.sampleRateHz}, FftSize: ${firstSpec.fftSize}, StepHz: ${String.format(Locale.US, "%.4f", firstSpec.freqStepHz)}")

        val header = buildList {
            add("Frequency_Hz")
            for ((pos, _) in positions) {
                add("${pos.code}_dBFS")
                add("${pos.code}_RelDb")
            }
        }.joinToString(",")
        sb.appendLine(header)

        val peakRefs = positions.map { (_, spec) -> spec.peakMagnitudeDbfs() }
        val binCount = positions.minOf { it.second.binCount }
        for (i in 0 until binCount) {
            val freq = firstSpec.frequencyAt(i)
            sb.append(String.format(Locale.US, "%.2f", freq))
            for (pIdx in positions.indices) {
                val spec = positions[pIdx].second
                val db = spec.magnitudesDbfs[i].toDouble()
                val rel = db - peakRefs[pIdx]
                sb.append(',')
                sb.append(String.format(Locale.US, "%.2f", db))
                sb.append(',')
                sb.append(String.format(Locale.US, "%.2f", rel))
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    fun formatDateTime(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(epochMs))
    }

    fun safeFileStem(guitarName: String, epochMs: Long): String {
        val cleaned = guitarName.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim('_')
            .ifEmpty { "guitar" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(epochMs))
        return "${cleaned}_$stamp"
    }

    private fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fs1(v: Double): String = String.format(Locale.US, "%+.1f", v)
}
