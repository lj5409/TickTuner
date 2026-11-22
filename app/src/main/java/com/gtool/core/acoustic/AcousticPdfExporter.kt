package com.gtool.core.acoustic

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import java.util.Locale
import kotlin.math.abs

/**
 * 基于 Android 原生 [PdfDocument] 的离线 A4 声学诊断报告生成器（零第三方库）：
 * - 第 1 页：报告封面头、乐器档案与工况、综合评分总览、核心共振模态实测表、关键力学特征
 * - 第 2 页：声学缺陷诊断、五维量化评级条、四大演奏风格适配度分析、微调与琴弦建议
 * - 第 3 页起：内嵌本次所有已测敲击点位的高分辨率 FFT 频谱图（每页 2 张，白底印刷风格）
 */
object AcousticPdfExporter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f
    private const val CONTENT_W = PAGE_W - MARGIN * 2

    private val colPrimary = Color.parseColor("#0D8A62")
    private val colInk = Color.parseColor("#141B18")
    private val colMuted = Color.parseColor("#54665E")
    private val colCard = Color.parseColor("#F3F6F4")
    private val colBorder = Color.parseColor("#D5DFDA")
    private val colWarm = Color.parseColor("#C67100")
    private val colDanger = Color.parseColor("#C9343E")

    fun writePdf(record: AcousticRecord, output: OutputStream) {
        val doc = PdfDocument()
        try {
            var pageNo = 1
            drawPage1(doc, record, pageNo++)
            drawPage2(doc, record, pageNo++)

            // 内嵌各点位高清 FFT 频谱图（每页最多 2 张）
            val positions = TapPosition.ordered.filter { record.spectra.containsKey(it) }
            val pairs = positions.chunked(2)
            for (group in pairs) {
                drawSpectraPage(doc, record, group, pageNo++)
            }
            doc.writeTo(output)
        } finally {
            doc.close()
        }
    }

    private fun drawPage1(doc: PdfDocument, record: AcousticRecord, pageNo: Int) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        val c = page.canvas
        val report = record.report
        var y = MARGIN

        // 顶部横幅
        val bannerRect = RectF(MARGIN, y, PAGE_W - MARGIN, y + 66f)
        val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colPrimary }
        c.drawRoundRect(bannerRect, 8f, 8f, bannerPaint)
        val bannerTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bannerSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D8FFF1")
            textSize = 9.5f
        }
        c.drawText("原声吉他声振模态与频响特性诊断报告", MARGIN + 14f, y + 27f, bannerTitle)
        c.drawText(
            "TickTuner 离线声学体检  ·  ${record.guitarName}  ·  ${AcousticReportFormatter.formatDateTime(record.createdAtEpochMs)}",
            MARGIN + 14f,
            y + 49f,
            bannerSub
        )
        y += 80f

        // 一、乐器档案与工况 + 总分卡（左右排布）
        val infoW = CONTENT_W * 0.67f
        val scoreW = CONTENT_W - infoW - 10f
        val infoRect = RectF(MARGIN, y, MARGIN + infoW, y + 96f)
        val scoreRect = RectF(infoRect.right + 10f, y, PAGE_W - MARGIN, y + 96f)
        drawCard(c, infoRect)
        drawCard(c, scoreRect)

        val headPaint = sectionPaint()
        c.drawText("一、乐器档案与测量工况", infoRect.left + 10f, infoRect.top + 18f, headPaint)
        val bodyLines = buildString {
            append("型号：${record.guitarName}    形制：${record.bodyShape.label}\n")
            append("拾音：${record.inputDeviceName}    基准：A4=${f1(record.a4Hz)} Hz\n")
            val flags = buildList {
                if (report.decoupleSkipped) add("跳过封音孔")
                if (report.hasQualityOverride) add("含人工放宽")
            }.joinToString("、").ifEmpty { "标准 5 点通过" }
            append("质检：最低 γ²=${String.format(Locale.US, "%.2f", report.minCoherence)}（$flags）")
            if (record.notes.isNotBlank()) append("\n备注：${record.notes.take(42)}")
        }
        drawMultiline(c, bodyLines, infoRect.left + 10f, infoRect.top + 24f, infoW - 20f, 8.8f, colInk)

        // 右侧综合分卡
        val scoreNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colPrimary
            textSize = 32f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val scoreSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colInk
            textSize = 10.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val scoreMutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colMuted
            textSize = 8.5f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("综合声学品质指数", scoreRect.centerX(), scoreRect.top + 18f, scoreMutedPaint)
        c.drawText("${report.overallScore}", scoreRect.centerX(), scoreRect.top + 56f, scoreNumPaint)
        c.drawText(report.qualityGrade, scoreRect.centerX(), scoreRect.top + 78f, scoreSubPaint)
        y += 110f

        // 二、核心共振模态实测数据表
        c.drawText("二、核心共振模态实测数据表", MARGIN, y + 12f, headPaint)
        y += 20f

        val colWidths = floatArrayOf(54f, 152f, 66f, 54f, 62f, 84f, CONTENT_W - 472f)
        val headers = arrayOf("模态", "物理定义", "频率(Hz)", "Q值", "相对dB", "音高偏差", "来源")
        y = drawTableRow(c, MARGIN, y, colWidths, headers, isHeader = true)

        val modalOrder = listOf(
            ModalId.A0, ModalId.T1, ModalId.B1, ModalId.T21, ModalId.T12, ModalId.T1_UNCOUPLED
        )
        for (id in modalOrder) {
            val peak = report.peaks[id] ?: continue
            val cents = if (id == ModalId.A0 || id == ModalId.T1 || id == ModalId.B1 || id == ModalId.T1_UNCOUPLED) {
                "${peak.nearestNoteName} (${String.format(Locale.US, "%+.0f", peak.centsOffset)}¢)"
            } else "-"
            val src = if (peak.isManualOverride) "${peak.sourcePosition.shortTitle}*" else peak.sourcePosition.shortTitle
            val row = arrayOf(
                id.symbol,
                id.description.take(14),
                f1(peak.frequencyHz),
                f1(peak.qFactor),
                fs1(peak.relativeDb),
                cents,
                src
            )
            y = drawTableRow(c, MARGIN, y, colWidths, row, isHeader = false)
        }
        y += 10f

        // 关键力学拓扑特征框
        val topoText = buildString {
            append("关键力学拓扑特征：")
            if (report.backTopDiffHz != null && report.backTopDiffSemitones != null) {
                append("面背板主频音程差 ${fs1(report.backTopDiffHz)} Hz（${fs1(report.backTopDiffSemitones)} 半音）；")
            }
            if (report.dipoleRatio != null) {
                val tag = if (abs(report.dipoleRatio - 2.0) <= 0.08) "高度接近 1:2 八度协同" else "偏离 1:2 八度协同"
                append("纵偶极/面板主频比值 ${String.format(Locale.US, "%.2f", report.dipoleRatio)}（$tag）")
            }
            if (report.decoupleShiftHz != null && report.a0DecoupleDropDb != null) {
                append("；封音孔后频率偏移 ${fs1(report.decoupleShiftHz)} Hz，A₀ 衰减 ${f1(report.a0DecoupleDropDb)} dB。")
            }
        }
        val topoRect = RectF(MARGIN, y, PAGE_W - MARGIN, y + 48f)
        drawCard(c, topoRect)
        drawMultiline(c, topoText, topoRect.left + 10f, topoRect.top + 8f, CONTENT_W - 20f, 9f, colInk)
        y += 62f

        // 三、五维量化评级条（放第 1 页下半部分，让第 1 页信息密度饱满）
        c.drawText("三、音质五维客观量化评级", MARGIN, y + 12f, headPaint)
        y += 22f
        val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colBorder }
        val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colPrimary }
        val dimTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colInk
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        for (dim in report.dimensionScores) {
            c.drawText("${dim.label}（权重 ${dim.weightPercent}%）", MARGIN, y + 10f, dimTitlePaint)
            val scoreRight = Paint(dimTitlePaint).apply {
                textAlign = Paint.Align.RIGHT
                color = when {
                    dim.score >= 85 -> colPrimary
                    dim.score >= 65 -> colWarm
                    else -> colDanger
                }
            }
            c.drawText("${dim.score} / 100", PAGE_W - MARGIN, y + 10f, scoreRight)
            val barRect = RectF(MARGIN, y + 15f, PAGE_W - MARGIN, y + 22f)
            c.drawRoundRect(barRect, 3.5f, 3.5f, barBgPaint)
            val fillRect = RectF(MARGIN, y + 15f, MARGIN + CONTENT_W * (dim.score / 100f), y + 22f)
            c.drawRoundRect(fillRect, 3.5f, 3.5f, barFillPaint)
            val h = drawMultiline(c, dim.basis, MARGIN, y + 25f, CONTENT_W, 8.2f, colMuted)
            y += 30f + h
        }

        drawPageFooter(c, pageNo)
        doc.finishPage(page)
    }

    private fun drawPage2(doc: PdfDocument, record: AcousticRecord, pageNo: Int) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        val c = page.canvas
        val report = record.report
        val headPaint = sectionPaint()
        var y = MARGIN

        // 四、声学缺陷与物理稳定性诊断
        c.drawText("四、声学缺陷与物理稳定性诊断", MARGIN, y + 12f, headPaint)
        y += 22f
        for (defect in report.defects) {
            val badgeColor = when (defect.severity) {
                DefectSeverity.OK -> colPrimary
                DefectSeverity.MODERATE -> colWarm
                DefectSeverity.SEVERE -> colDanger
            }
            val blockText = "${defect.title}（${defect.severity.label}）：${defect.summary}\n${defect.detail}"
            val textHeight = measureMultilineHeight(blockText, CONTENT_W - 26f, 8.8f)
            val box = RectF(MARGIN, y, PAGE_W - MARGIN, y + textHeight + 18f)
            drawCard(c, box)
            val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = badgeColor }
            c.drawRoundRect(RectF(box.left, box.top, box.left + 5f, box.bottom), 3f, 3f, stripe)
            drawMultiline(c, blockText, box.left + 14f, box.top + 8f, CONTENT_W - 24f, 8.8f, colInk)
            y = box.bottom + 10f
        }

        y += 8f
        // 五、演奏风格与曲风适配建议
        c.drawText("五、演奏风格与曲风适配建议", MARGIN, y + 12f, headPaint)
        y += 22f
        val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colBorder }
        val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colPrimary }
        val styleTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colInk
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        for ((idx, m) in report.styleMatches.withIndex()) {
            val tag = if (idx == 0) " [最佳适配]" else ""
            c.drawText("${m.style.label}$tag", MARGIN, y + 10f, styleTitle)
            val pctPaint = Paint(styleTitle).apply {
                textAlign = Paint.Align.RIGHT
                color = if (idx == 0) colPrimary else colMuted
            }
            c.drawText("${m.matchPercent}%", PAGE_W - MARGIN, y + 10f, pctPaint)
            c.drawRoundRect(RectF(MARGIN, y + 14f, PAGE_W - MARGIN, y + 20f), 3f, 3f, barBg)
            barFill.color = if (idx == 0) colPrimary else Color.parseColor("#4D8FBF")
            c.drawRoundRect(RectF(MARGIN, y + 14f, MARGIN + CONTENT_W * (m.matchPercent / 100f), y + 20f), 3f, 3f, barFill)
            val h = drawMultiline(c, m.reason, MARGIN, y + 23f, CONTENT_W, 8.2f, colMuted)
            y += 28f + h
        }

        if (report.notRecommendedHint.isNotBlank()) {
            val h = measureMultilineHeight("局限提示：${report.notRecommendedHint}", CONTENT_W - 20f, 8.6f)
            val box = RectF(MARGIN, y + 4f, PAGE_W - MARGIN, y + h + 18f)
            drawCard(c, box)
            drawMultiline(c, "不推荐流派及局限提示：${report.notRecommendedHint}", box.left + 10f, box.top + 7f, CONTENT_W - 20f, 8.6f, colWarm)
            y = box.bottom + 14f
        }

        // 六、微调与保养建议
        c.drawText("六、针对性微调与保养建议 (Luthier Voicing Guide)", MARGIN, y + 12f, headPaint)
        y += 20f
        val adviceText = "• 琴弦张力匹配建议：${report.stringTensionAdvice}\n• 物理结构微调方向：${report.voicingAdvice}"
        val advH = measureMultilineHeight(adviceText, CONTENT_W - 20f, 8.8f)
        val advRect = RectF(MARGIN, y, PAGE_W - MARGIN, (y + advH + 20f).coerceAtMost(PAGE_H - 46f))
        drawCard(c, advRect)
        drawMultiline(c, adviceText, advRect.left + 10f, advRect.top + 9f, CONTENT_W - 20f, 8.8f, colInk)

        drawPageFooter(c, pageNo)
        doc.finishPage(page)
    }

    private fun drawSpectraPage(
        doc: PdfDocument,
        record: AcousticRecord,
        positions: List<TapPosition>,
        pageNo: Int
    ) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        val c = page.canvas
        val headPaint = sectionPaint()
        var y = MARGIN

        c.drawText("附：各敲击点位高分辨率 FFT 频谱图", MARGIN, y + 12f, headPaint)
        y += 24f

        val slotH = (PAGE_H - MARGIN - 30f - y - 16f) / 2f
        for (pos in positions) {
            val chartBmp = AcousticChartBitmapRenderer.renderSingleSpectrumBitmap(
                record = record,
                position = pos,
                options = SpectrumChartOptions(
                    minHz = 50.0,
                    maxHz = if (pos == TapPosition.BRIDGE) 5000.0 else 1200.0,
                    logScale = true,
                    normalizeToPeak = true
                ),
                lightTheme = true,
                widthPx = 1600,
                heightPx = 940
            )
            try {
                val aspect = chartBmp.width.toFloat() / chartBmp.height.toFloat()
                var drawW = CONTENT_W
                var drawH = drawW / aspect
                if (drawH > slotH) {
                    drawH = slotH
                    drawW = drawH * aspect
                }
                val left = MARGIN + (CONTENT_W - drawW) / 2f
                val dst = RectF(left, y, left + drawW, y + drawH)
                val src = Rect(0, 0, chartBmp.width, chartBmp.height)
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colBorder
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                }
                c.drawBitmap(chartBmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                c.drawRect(dst, borderPaint)
                y += slotH + 14f
            } finally {
                chartBmp.recycle()
            }
        }

        drawPageFooter(c, pageNo)
        doc.finishPage(page)
    }

    private fun drawTableRow(
        c: Canvas,
        startX: Float,
        topY: Float,
        widths: FloatArray,
        cells: Array<String>,
        isHeader: Boolean
    ): Float {
        val rowH = if (isHeader) 22f else 20f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isHeader) Color.parseColor("#E1EFE9") else Color.WHITE
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colBorder
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colInk
            textSize = if (isHeader) 8.5f else 8.4f
            typeface = if (isHeader) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }
        val rect = RectF(startX, topY, startX + widths.sum(), topY + rowH)
        c.drawRect(rect, bg)
        c.drawRect(rect, border)

        var x = startX
        for (i in cells.indices) {
            c.drawText(cells[i], x + 4f, topY + rowH - 6.5f, textPaint)
            x += widths[i]
            if (i < cells.lastIndex) {
                c.drawLine(x, topY, x, topY + rowH, border)
            }
        }
        return topY + rowH
    }

    private fun drawCard(c: Canvas, rect: RectF) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colCard }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colBorder
            style = Paint.Style.STROKE
            strokeWidth = 0.9f
        }
        c.drawRoundRect(rect, 6f, 6f, bg)
        c.drawRoundRect(rect, 6f, 6f, stroke)
    }

    private fun drawMultiline(
        c: Canvas,
        text: String,
        x: Float,
        y: Float,
        width: Float,
        textSizeSp: Float,
        color: Int
    ): Float {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSizeSp
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width.toInt().coerceAtLeast(20))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2.5f, 1.0f)
            .build()
        c.save()
        c.translate(x, y)
        layout.draw(c)
        c.restore()
        return layout.height.toFloat()
    }

    private fun measureMultilineHeight(text: String, width: Float, textSizeSp: Float): Float {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textSizeSp }
        return StaticLayout.Builder.obtain(text, 0, text.length, tp, width.toInt().coerceAtLeast(20))
            .setLineSpacing(2.5f, 1.0f)
            .build().height.toFloat()
    }

    private fun sectionPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colPrimary
        textSize = 11.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun drawPageFooter(c: Canvas, pageNo: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colMuted
            textSize = 8f
            textAlign = Paint.Align.CENTER
        }
        c.drawText("TickTuner 离线吉他声学分析报告  ·  第 $pageNo 页", PAGE_W / 2f, PAGE_H - 18f, p)
    }

    private fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fs1(v: Double): String = String.format(Locale.US, "%+.1f", v)
}
