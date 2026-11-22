package com.gtool.core.acoustic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 频谱图视图配置（单图、对比图、PNG 导出及 PDF 内嵌图共用）。
 */
data class SpectrumChartOptions(
    val minHz: Double = 50.0,
    val maxHz: Double = 1000.0,
    val logScale: Boolean = true,
    val normalizeToPeak: Boolean = true
)

/**
 * 基于 Android 原生 Bitmap/Canvas 的高分辨率 FFT 频谱导出渲染器：
 * - 单点位 FFT 频谱 PNG（注明吉他名、敲击部位、共振峰代号与精确频率）
 * - 双记录横向叠图对比 PNG（支持自定义曲线配色、前后叠放顺序与共振峰差值对照）
 * - 支持深色主题（相册分享）与白底印刷主题（A4 PDF 内嵌图）
 */
object AcousticChartBitmapRenderer {

    private data class Palette(
        val bg: Int,
        val cardBg: Int,
        val grid: Int,
        val axis: Int,
        val textMain: Int,
        val textMuted: Int,
        val badgeBg: Int
    )

    private val darkPalette = Palette(
        bg = Color.parseColor("#0E1210"),
        cardBg = Color.parseColor("#161D19"),
        grid = Color.parseColor("#2A3832"),
        axis = Color.parseColor("#4B5F56"),
        textMain = Color.parseColor("#E9F2ED"),
        textMuted = Color.parseColor("#90A69B"),
        badgeBg = Color.parseColor("#1F2A25")
    )

    private val lightPalette = Palette(
        bg = Color.WHITE,
        cardBg = Color.parseColor("#F5F7F6"),
        grid = Color.parseColor("#DCE3DF"),
        axis = Color.parseColor("#8A9B93"),
        textMain = Color.parseColor("#141B18"),
        textMuted = Color.parseColor("#54665E"),
        badgeBg = Color.parseColor("#EAF0ED")
    )

    fun renderSingleSpectrumBitmap(
        record: AcousticRecord,
        position: TapPosition,
        options: SpectrumChartOptions = SpectrumChartOptions(50.0, 2000.0, logScale = true, normalizeToPeak = true),
        lightTheme: Boolean = false,
        widthPx: Int = 1920,
        heightPx: Int = 1120
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pal = if (lightTheme) lightPalette else darkPalette
        val scale = widthPx / 1920f
        canvas.drawColor(pal.bg)

        val spec = record.spectra[position] ?: return bmp
        val peaksForPos = peaksRelevantToPosition(record.report, position)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.textMain
            textSize = 44f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.textMuted
            textSize = 26f * scale
        }

        val padX = 72f * scale
        val headerTop = 58f * scale
        canvas.drawText(
            "${record.guitarName} · ${position.title} (${position.code})",
            padX,
            headerTop + 34f * scale,
            titlePaint
        )
        val metaLine = buildString {
            append("${record.bodyShape.label}  |  ${AcousticReportFormatter.formatDateTime(record.createdAtEpochMs)}")
            append("  |  目标模态：${position.targetModesLabel}")
            append("  |  γ²=${String.format(Locale.US, "%.2f", spec.coherenceScore)}")
            append("  |  ${if (options.normalizeToPeak) "相对 0 dB" else "原始 dBFS"}")
        }
        canvas.drawText(metaLine, padX, headerTop + 76f * scale, subPaint)

        // 绘图区矩形
        val plotRect = RectF(
            padX + 68f * scale,
            headerTop + 114f * scale,
            widthPx - padX,
            heightPx - 168f * scale
        )

        val peakRef = spec.peakMagnitudeDbfs(options.minHz, options.maxHz)
        val (minDb, maxDb) = computeDbBounds(listOf(spec), options)

        drawGridAndAxes(canvas, plotRect, options, minDb, maxDb, pal, scale)

        val curveColor = if (lightTheme) Color.parseColor("#0D9468") else Color.parseColor("#2EE6A8")
        drawSpectrumCurve(
            canvas = canvas,
            rect = plotRect,
            spectrum = spec,
            options = options,
            refDb = peakRef,
            minDb = minDb,
            maxDb = maxDb,
            strokeColor = curveColor,
            strokeWidthPx = 4.2f * scale,
            fillAlpha = if (lightTheme) 36 else 52
        )

        // 绘制共振峰标注气泡（自动避让）
        drawPeakAnnotations(
            canvas = canvas,
            rect = plotRect,
            spectrum = spec,
            peaks = peaksForPos,
            options = options,
            refDb = peakRef,
            minDb = minDb,
            maxDb = maxDb,
            pal = pal,
            scale = scale,
            accentColor = if (lightTheme) Color.parseColor("#C26D00") else Color.parseColor("#FFB86B")
        )

        // 底部模态汇总条
        val footerRect = RectF(padX, heightPx - 118f * scale, widthPx - padX, heightPx - 36f * scale)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.cardBg }
        canvas.drawRoundRect(footerRect, 16f * scale, 16f * scale, cardPaint)
        val footerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.textMain
            textSize = 25f * scale
        }
        val summaryLine = if (peaksForPos.isEmpty()) {
            "TickTuner 离线声学分析 · 采样率 ${spec.sampleRateHz} Hz / FFT ${spec.fftSize} 点"
        } else {
            peaksForPos.joinToString("    ") { p ->
                "${p.modalId.symbol}: ${String.format(Locale.US, "%.1f", p.frequencyHz)} Hz (Q=${String.format(Locale.US, "%.1f", p.qFactor)}, ${p.nearestNoteName})"
            }
        }
        canvas.drawText(
            summaryLine,
            footerRect.left + 26f * scale,
            footerRect.centerY() + 9f * scale,
            footerTextPaint
        )

        return bmp
    }

    fun renderComparisonBitmap(
        recordA: AcousticRecord,
        recordB: AcousticRecord,
        position: TapPosition,
        colorA: Int,
        colorB: Int,
        recordAOnTop: Boolean,
        options: SpectrumChartOptions,
        widthPx: Int = 2048,
        heightPx: Int = 1152
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pal = darkPalette
        val scale = widthPx / 2048f
        canvas.drawColor(pal.bg)

        val specA = recordA.spectra[position] ?: return bmp
        val specB = recordB.spectra[position] ?: return bmp

        val padX = 72f * scale
        val headerTop = 50f * scale

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.textMain
            textSize = 42f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.textMuted
            textSize = 25f * scale
        }

        canvas.drawText(
            "FFT 频谱叠加对比 · ${position.title} (${position.code})",
            padX,
            headerTop + 34f * scale,
            titlePaint
        )
        canvas.drawText(
            "${if (options.normalizeToPeak) "相对峰值 0 dB 对齐" else "原始采集电平 dBFS"}  |  " +
                "${if (options.logScale) "对数频率轴" else "线性频率轴"}  |  " +
                "叠放顺序：${if (recordAOnTop) "A 在上" else "B 在上"}",
            padX,
            headerTop + 72f * scale,
            subPaint
        )

        // 双记录图例行
        val legendY = headerTop + 110f * scale
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.textMain
            textSize = 26f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val labelA = "A: ${recordA.guitarName} (${AcousticReportFormatter.formatDateTime(recordA.createdAtEpochMs)})"
        val labelB = "B: ${recordB.guitarName} (${AcousticReportFormatter.formatDateTime(recordB.createdAtEpochMs)})"

        dotPaint.color = colorA
        canvas.drawCircle(padX + 12f * scale, legendY - 8f * scale, 10f * scale, dotPaint)
        canvas.drawText(labelA, padX + 32f * scale, legendY, legendPaint)

        val bStartX = max(padX + 32f * scale + legendPaint.measureText(labelA) + 54f * scale, widthPx * 0.48f)
        dotPaint.color = colorB
        canvas.drawCircle(bStartX, legendY - 8f * scale, 10f * scale, dotPaint)
        canvas.drawText(labelB, bStartX + 20f * scale, legendY, legendPaint)

        val plotRect = RectF(
            padX + 68f * scale,
            legendY + 26f * scale,
            widthPx - padX,
            heightPx - 180f * scale
        )

        val refA = specA.peakMagnitudeDbfs(options.minHz, options.maxHz)
        val refB = specB.peakMagnitudeDbfs(options.minHz, options.maxHz)
        val (minDb, maxDb) = computeDbBounds(listOf(specA, specB), options)

        drawGridAndAxes(canvas, plotRect, options, minDb, maxDb, pal, scale)

        // 按选定叠放顺序先绘下层（稍带透明），再绘上层（实线加粗）
        if (recordAOnTop) {
            drawSpectrumCurve(canvas, plotRect, specB, options, refB, minDb, maxDb, withAlpha(colorB, 185), 3.6f * scale, 28)
            drawSpectrumCurve(canvas, plotRect, specA, options, refA, minDb, maxDb, colorA, 4.8f * scale, 40)
        } else {
            drawSpectrumCurve(canvas, plotRect, specA, options, refA, minDb, maxDb, withAlpha(colorA, 185), 3.6f * scale, 28)
            drawSpectrumCurve(canvas, plotRect, specB, options, refB, minDb, maxDb, colorB, 4.8f * scale, 40)
        }

        // 在图上标注该点位关键模态（区分 A / B 颜色圆点）
        val targetModals = modalIdsForPosition(position)
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        for (mid in targetModals) {
            val pA = recordA.report.peaks[mid]
            val pB = recordB.report.peaks[mid]
            if (pA != null && pA.frequencyHz in options.minHz..options.maxHz) {
                val x = freqToX(pA.frequencyHz, plotRect, options)
                val yDb = if (options.normalizeToPeak) pA.amplitudeDbfs - refA else pA.amplitudeDbfs
                val y = dbToY(yDb, plotRect, minDb, maxDb)
                markerPaint.color = colorA
                canvas.drawCircle(x, y, 8f * scale, markerPaint)
                tagText.color = colorA
                canvas.drawText("${mid.symbol}(A) ${String.format(Locale.US, "%.1f", pA.frequencyHz)}", x + 10f * scale, max(plotRect.top + 26f * scale, y - 10f * scale), tagText)
            }
            if (pB != null && pB.frequencyHz in options.minHz..options.maxHz) {
                val x = freqToX(pB.frequencyHz, plotRect, options)
                val yDb = if (options.normalizeToPeak) pB.amplitudeDbfs - refB else pB.amplitudeDbfs
                val y = dbToY(yDb, plotRect, minDb, maxDb)
                markerPaint.color = colorB
                canvas.drawCircle(x, y, 8f * scale, markerPaint)
                tagText.color = colorB
                canvas.drawText("${mid.symbol}(B) ${String.format(Locale.US, "%.1f", pB.frequencyHz)}", x + 10f * scale, min(plotRect.bottom - 14f * scale, y + 26f * scale), tagText)
            }
        }

        // 底部共振峰对比差值栏
        val footerRect = RectF(padX, heightPx - 126f * scale, widthPx - padX, heightPx - 34f * scale)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.cardBg }
        canvas.drawRoundRect(footerRect, 16f * scale, 16f * scale, cardPaint)
        val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.textMain
            textSize = 24f * scale
        }
        val diffs = targetModals.mapNotNull { mid ->
            val pA = recordA.report.peaks[mid]
            val pB = recordB.report.peaks[mid]
            if (pA == null || pB == null) null
            else {
                val dHz = pB.frequencyHz - pA.frequencyHz
                val eA = if (options.normalizeToPeak) pA.amplitudeDbfs - refA else pA.amplitudeDbfs
                val eB = if (options.normalizeToPeak) pB.amplitudeDbfs - refB else pB.amplitudeDbfs
                val dDb = eB - eA
                "${mid.symbol}: A ${String.format(Locale.US, "%.1f", pA.frequencyHz)} → B ${String.format(Locale.US, "%.1f", pB.frequencyHz)} Hz (${String.format(Locale.US, "%+.1f", dHz)} Hz, ${String.format(Locale.US, "%+.1f", dDb)} dB)"
            }
        }.joinToString("   |   ").ifEmpty { "TickTuner 离线频谱对比导出" }

        canvas.drawText(diffs, footerRect.left + 26f * scale, footerRect.centerY() + 8f * scale, footPaint)
        return bmp
    }

    fun peaksRelevantToPosition(report: AcousticReport, position: TapPosition): List<DetectedPeak> {
        val ids = modalIdsForPosition(position)
        return ids.mapNotNull { report.peaks[it] }
    }

    fun modalIdsForPosition(position: TapPosition): List<ModalId> = when (position) {
        TapPosition.BRIDGE -> listOf(ModalId.A0, ModalId.T1, ModalId.B1, ModalId.T31)
        TapPosition.DIPOLE_L -> listOf(ModalId.A0, ModalId.T1, ModalId.T21)
        TapPosition.DIPOLE_V -> listOf(ModalId.A0, ModalId.T1, ModalId.T12)
        TapPosition.BACK -> listOf(ModalId.A0, ModalId.B1)
        TapPosition.DECOUPLE -> listOf(ModalId.T1_UNCOUPLED, ModalId.A0)
    }

    private fun computeDbBounds(
        spectra: List<PositionSpectrum>,
        options: SpectrumChartOptions
    ): Pair<Double, Double> {
        if (options.normalizeToPeak) {
            return -60.0 to 6.0
        }
        var maxPeak = -100.0
        for (s in spectra) {
            maxPeak = max(maxPeak, s.peakMagnitudeDbfs(options.minHz, options.maxHz))
        }
        val top = (ceil(maxPeak / 10.0) * 10.0 + 5.0).coerceIn(-40.0, 5.0)
        return (top - 65.0) to top
    }

    private fun drawGridAndAxes(
        canvas: Canvas,
        rect: RectF,
        options: SpectrumChartOptions,
        minDb: Double,
        maxDb: Double,
        pal: Palette,
        scale: Float
    ) {
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.cardBg }
        canvas.drawRoundRect(rect, 12f * scale, 12f * scale, cardPaint)

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.grid
            strokeWidth = 1.6f * scale
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.axis
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.textMuted
            textSize = 22f * scale
        }

        // 水平 dB 网格线
        val dbStep = 10.0
        var db = ceil(minDb / dbStep) * dbStep
        while (db <= maxDb) {
            val y = dbToY(db, rect, minDb, maxDb)
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
            val txt = "${db.toInt()}"
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(txt, rect.left - 12f * scale, y + 7f * scale, labelPaint)
            db += dbStep
        }

        // 垂直频率网格线
        val ticks = frequencyTicks(options.minHz, options.maxHz, options.logScale)
        labelPaint.textAlign = Paint.Align.CENTER
        for (hz in ticks) {
            val x = freqToX(hz, rect, options)
            if (x < rect.left + 4f || x > rect.right - 4f) continue
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
            val txt = if (hz >= 1000.0) {
                val k = hz / 1000.0
                if (abs(k - k.toInt()) < 0.01) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
            } else {
                "${hz.toInt()}"
            }
            canvas.drawText(txt, x, rect.bottom + 30f * scale, labelPaint)
        }
        canvas.drawRoundRect(rect, 12f * scale, 12f * scale, borderPaint)
    }

    private fun drawSpectrumCurve(
        canvas: Canvas,
        rect: RectF,
        spectrum: PositionSpectrum,
        options: SpectrumChartOptions,
        refDb: Double,
        minDb: Double,
        maxDb: Double,
        strokeColor: Int,
        strokeWidthPx: Float,
        fillAlpha: Int
    ) {
        val mags = spectrum.magnitudesDbfs
        if (mags.size < 2) return
        val startIdx = spectrum.localIndexForFrequency(options.minHz)
        val endIdx = spectrum.localIndexForFrequency(options.maxHz)
        if (endIdx <= startIdx) return

        val linePath = Path()
        val fillPath = Path()
        var started = false
        var firstX = rect.left
        var lastX = rect.right

        // 按像素列采样峰值包络，既保证高密频段不丢失窄峰又极度平滑
        val pixelSteps = rect.width().toInt().coerceIn(300, 1800)
        var prevBin = startIdx
        for (px in 0..pixelSteps) {
            val frac = px.toDouble() / pixelSteps
            val hz = if (options.logScale) {
                options.minHz * Math.pow(options.maxHz / options.minHz, frac)
            } else {
                options.minHz + (options.maxHz - options.minHz) * frac
            }
            val bin = spectrum.localIndexForFrequency(hz)
            val lo = min(prevBin, bin).coerceIn(startIdx, endIdx)
            val hi = max(prevBin, bin).coerceIn(startIdx, endIdx)
            var peakVal = Float.NEGATIVE_INFINITY
            for (k in lo..hi) {
                if (mags[k] > peakVal) peakVal = mags[k]
            }
            prevBin = bin
            val displayDb = if (options.normalizeToPeak) peakVal.toDouble() - refDb else peakVal.toDouble()
            val x = rect.left + (rect.width() * frac).toFloat()
            val y = dbToY(displayDb, rect, minDb, maxDb)
            if (!started) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, rect.bottom)
                fillPath.lineTo(x, y)
                firstX = x
                started = true
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastX = x
        }
        if (!started) return
        fillPath.lineTo(lastX, rect.bottom)
        fillPath.lineTo(firstX, rect.bottom)
        fillPath.close()

        canvas.save()
        canvas.clipRect(rect)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                withAlpha(strokeColor, fillAlpha),
                withAlpha(strokeColor, 0),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(fillPath, fillPaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(linePath, strokePaint)
        canvas.restore()
    }

    private fun drawPeakAnnotations(
        canvas: Canvas,
        rect: RectF,
        spectrum: PositionSpectrum,
        peaks: List<DetectedPeak>,
        options: SpectrumChartOptions,
        refDb: Double,
        minDb: Double,
        maxDb: Double,
        pal: Palette,
        scale: Float,
        accentColor: Int
    ) {
        val visible = peaks.filter { it.frequencyHz in options.minHz..options.maxHz }
            .sortedBy { it.frequencyHz }
        if (visible.isEmpty()) return

        val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(accentColor, 150)
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
            pathEffect = DashPathEffect(floatArrayOf(8f * scale, 6f * scale), 0f)
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.badgeBg }
        val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 2f * scale
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.textMain
            textSize = 23f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val placedRects = mutableListOf<RectF>()
        for (peak in visible) {
            val x = freqToX(peak.frequencyHz, rect, options)
            val localDb = spectrum.magnitudeAtFrequency(peak.frequencyHz)
            val yDb = if (options.normalizeToPeak) localDb - refDb else localDb
            val y = dbToY(yDb, rect, minDb, maxDb)

            canvas.drawLine(x, y, x, rect.bottom, dashPaint)
            canvas.drawCircle(x, y, 7.5f * scale, dotPaint)

            val label = "${peak.modalId.symbol} ${String.format(Locale.US, "%.1f", peak.frequencyHz)} Hz"
            val tw = textPaint.measureText(label)
            val bw = tw + 22f * scale
            val bh = 38f * scale
            var bx = (x - bw / 2f).coerceIn(rect.left + 6f * scale, rect.right - bw - 6f * scale)
            var by = (y - bh - 16f * scale).coerceAtLeast(rect.top + 8f * scale)

            // 简易纵向碰撞避让
            var attempts = 0
            while (attempts < 5) {
                val cand = RectF(bx, by, bx + bw, by + bh)
                val collides = placedRects.any { RectF.intersects(it, cand) }
                if (!collides) break
                by += bh + 8f * scale
                if (by + bh > rect.bottom - 10f * scale) {
                    by = rect.top + 8f * scale + (attempts * 14f * scale)
                }
                attempts++
            }
            val box = RectF(bx, by, bx + bw, by + bh)
            placedRects.add(box)
            canvas.drawRoundRect(box, 10f * scale, 10f * scale, badgeBgPaint)
            canvas.drawRoundRect(box, 10f * scale, 10f * scale, badgeBorderPaint)
            canvas.drawText(label, box.left + 11f * scale, box.centerY() + 8f * scale, textPaint)
        }
    }

    private fun freqToX(hz: Double, rect: RectF, options: SpectrumChartOptions): Float {
        val clamped = hz.coerceIn(options.minHz, options.maxHz)
        val frac = if (options.logScale) {
            ln(clamped / options.minHz) / ln(options.maxHz / options.minHz)
        } else {
            (clamped - options.minHz) / (options.maxHz - options.minHz)
        }
        return rect.left + (rect.width() * frac).toFloat()
    }

    private fun dbToY(db: Double, rect: RectF, minDb: Double, maxDb: Double): Float {
        val frac = ((db - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)
        return rect.bottom - (rect.height() * frac).toFloat()
    }

    fun frequencyTicks(minHz: Double, maxHz: Double, logScale: Boolean): List<Double> {
        if (logScale) {
            val base = listOf(
                50.0, 70.0, 100.0, 150.0, 200.0, 300.0, 400.0, 500.0,
                700.0, 1000.0, 1500.0, 2000.0, 3000.0, 5000.0
            )
            return base.filter { it in minHz..maxHz }
        }
        val span = maxHz - minHz
        val step = when {
            span <= 400.0 -> 50.0
            span <= 1000.0 -> 100.0
            span <= 2500.0 -> 250.0
            else -> 500.0
        }
        val result = mutableListOf<Double>()
        var cur = ceil(minHz / step) * step
        while (cur <= maxHz) {
            result.add(cur)
            cur += step
        }
        return result
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
