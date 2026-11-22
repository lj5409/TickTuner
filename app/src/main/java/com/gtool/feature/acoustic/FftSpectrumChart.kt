package com.gtool.feature.acoustic

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.acoustic.AcousticChartBitmapRenderer
import com.gtool.core.acoustic.DetectedPeak
import com.gtool.core.acoustic.ModalId
import com.gtool.core.acoustic.PositionSpectrum
import com.gtool.core.acoustic.SpectrumChartOptions
import com.gtool.core.music.Note
import com.gtool.core.music.centsFrom
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.InfoBlue
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 绘制在 [FftSpectrumChart] 上的一条频谱曲线。
 */
data class SpectrumCurveSeries(
    val label: String,
    val spectrum: PositionSpectrum,
    val color: Color,
    val peaks: List<DetectedPeak> = emptyList(),
    val isTopLayer: Boolean = true
)

/**
 * 高精度交互式 FFT 频谱 Canvas 组件（同时支持单点频谱展示、共振峰手动微调与双记录叠图对比）：
 * - 注明敲击部位、共振峰代号（A₀ / T₁ / B₁ / T₂,₁ / T₁,₂ 等）与精确频率（自动防碰撞排布）
 * - 支持对数 / 线性频率轴、0 dB 相对峰值 / 原始 dBFS 切换
 * - 支持单指触摸/拖动十字游标精准读频（Hz、最近音名+音分、dB 及双曲线 ΔdB）
 * - 微调模式下点击或拖动曲线即可指定共振峰新频率，松手吸附局部峰值重算报告
 */
@Composable
fun FftSpectrumChart(
    headerTitle: String,
    seriesList: List<SpectrumCurveSeries>,
    options: SpectrumChartOptions,
    a4Hz: Double = 440.0,
    useFlats: Boolean = false,
    tuningModalTarget: ModalId? = null,
    onManualPeakSelected: ((ModalId, Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var cursorFreqHz by remember(options, seriesList.size) { mutableStateOf<Double?>(null) }

    // 按叠放顺序排序：先画下层，再画上层
    val orderedSeries = remember(seriesList) {
        seriesList.sortedBy { if (it.isTopLayer) 1 else 0 }
    }

    val (minDb, maxDb) = remember(orderedSeries, options) {
        if (options.normalizeToPeak) {
            -60.0 to 6.0
        } else {
            var peak = -100.0
            for (s in orderedSeries) {
                peak = max(peak, s.spectrum.peakMagnitudeDbfs(options.minHz, options.maxHz))
            }
            val top = (ceil(peak / 10.0) * 10.0 + 5.0).coerceIn(-40.0, 5.0)
            (top - 65.0) to top
        }
    }

    val refs = remember(orderedSeries, options) {
        orderedSeries.map { it.spectrum.peakMagnitudeDbfs(options.minHz, options.maxHz) }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        // 顶部标题行 + 游标实时读数
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = headerTitle,
                color = TextMain,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            val cHz = cursorFreqHz
            if (cHz != null && orderedSeries.isNotEmpty()) {
                val note = Note.fromFrequency(cHz, a4Hz)
                val cents = centsFrom(cHz, note.frequency(a4Hz))
                val readout = if (seriesList.size == 2) {
                    val sA = seriesList[0]
                    val sB = seriesList[1]
                    val refA = sA.spectrum.peakMagnitudeDbfs(options.minHz, options.maxHz)
                    val refB = sB.spectrum.peakMagnitudeDbfs(options.minHz, options.maxHz)
                    val vA = sA.spectrum.magnitudeAtFrequency(cHz) - (if (options.normalizeToPeak) refA else 0.0)
                    val vB = sB.spectrum.magnitudeAtFrequency(cHz) - (if (options.normalizeToPeak) refB else 0.0)
                    "${f1(cHz)} Hz (${note.name(useFlats)}) · A:${f1(vA)} B:${f1(vB)} Δ:${String.format(Locale.US, "%+.1f", vB - vA)} dB"
                } else {
                    val s0 = orderedSeries.last()
                    val ref0 = refs.last()
                    val v0 = s0.spectrum.magnitudeAtFrequency(cHz) - (if (options.normalizeToPeak) ref0 else 0.0)
                    "${f1(cHz)} Hz · ${note.name(useFlats)}(${String.format(Locale.US, "%+.0f", cents)}¢) · ${f1(v0)} dB"
                }
                Text(
                    text = readout,
                    color = InfoBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            } else if (tuningModalTarget != null) {
                Text(
                    text = "点选/拖动图面定位 ${tuningModalTarget.symbol}",
                    color = Warm,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(
                    text = if (options.normalizeToPeak) "相对 0 dB · 触屏读频" else "原始 dBFS · 触屏读频",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val leftPadPx = with(density) { 36.dp.toPx() }
            val rightPadPx = with(density) { 10.dp.toPx() }
            val topPadPx = with(density) { 14.dp.toPx() }
            val bottomPadPx = with(density) { 24.dp.toPx() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(options, tuningModalTarget, onManualPeakSelected) {
                        detectTapGestures { offset ->
                            val plotW = (size.width - leftPadPx - rightPadPx).coerceAtLeast(1f)
                            val frac = ((offset.x - leftPadPx) / plotW).coerceIn(0f, 1f).toDouble()
                            val hz = fracToFreq(frac, options)
                            cursorFreqHz = hz
                            if (tuningModalTarget != null && onManualPeakSelected != null) {
                                onManualPeakSelected(tuningModalTarget, hz)
                            }
                        }
                    }
                    .pointerInput(options, tuningModalTarget, onManualPeakSelected) {
                        detectDragGestures(
                            onDragEnd = {
                                val target = tuningModalTarget
                                val hz = cursorFreqHz
                                if (target != null && hz != null && onManualPeakSelected != null) {
                                    onManualPeakSelected(target, hz)
                                }
                            }
                        ) { change, _ ->
                            val plotW = (size.width - leftPadPx - rightPadPx).coerceAtLeast(1f)
                            val frac = ((change.position.x - leftPadPx) / plotW).coerceIn(0f, 1f).toDouble()
                            cursorFreqHz = fracToFreq(frac, options)
                        }
                    }
            ) {
                val plotLeft = leftPadPx
                val plotTop = topPadPx
                val plotRight = size.width - rightPadPx
                val plotBottom = size.height - bottomPadPx
                val plotW = (plotRight - plotLeft).coerceAtLeast(10f)
                val plotH = (plotBottom - plotTop).coerceAtLeast(10f)

                fun xFor(hz: Double): Float {
                    val clamped = hz.coerceIn(options.minHz, options.maxHz)
                    val f = if (options.logScale) {
                        ln(clamped / options.minHz) / ln(options.maxHz / options.minHz)
                    } else {
                        (clamped - options.minHz) / (options.maxHz - options.minHz)
                    }
                    return plotLeft + (plotW * f).toFloat()
                }

                fun yFor(db: Double): Float {
                    val f = ((db - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)
                    return plotBottom - (plotH * f).toFloat()
                }

                // 若处于微调某个模态模式，高亮该模态典型推荐频段
                if (tuningModalTarget != null) {
                    val bandStart = xFor(tuningModalTarget.minHz.coerceAtLeast(options.minHz))
                    val bandEnd = xFor(tuningModalTarget.maxHz.coerceAtMost(options.maxHz))
                    if (bandEnd > bandStart) {
                        drawRect(
                            color = Warm.copy(alpha = 0.12f),
                            topLeft = Offset(bandStart, plotTop),
                            size = androidx.compose.ui.geometry.Size(bandEnd - bandStart, plotH)
                        )
                    }
                }

                val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = TextMuted.toArgb()
                    textSize = with(density) { 10.sp.toPx() }
                }

                // 水平 dB 网格线
                var db = ceil(minDb / 10.0) * 10.0
                while (db <= maxDb) {
                    val y = yFor(db)
                    drawLine(
                        color = FretWire.copy(alpha = 0.42f),
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 1f
                    )
                    drawIntoCanvas { canvas ->
                        axisPaint.textAlign = Paint.Align.RIGHT
                        canvas.nativeCanvas.drawText(
                            "${db.toInt()}",
                            plotLeft - with(density) { 5.dp.toPx() },
                            y + with(density) { 3.5.dp.toPx() },
                            axisPaint
                        )
                    }
                    db += 10.0
                }

                // 垂直频率网格线
                val ticks = AcousticChartBitmapRenderer.frequencyTicks(
                    options.minHz, options.maxHz, options.logScale
                )
                for (hz in ticks) {
                    val x = xFor(hz)
                    if (x < plotLeft + 2f || x > plotRight - 2f) continue
                    drawLine(
                        color = FretWire.copy(alpha = 0.42f),
                        start = Offset(x, plotTop),
                        end = Offset(x, plotBottom),
                        strokeWidth = 1f
                    )
                    val label = if (hz >= 1000.0) {
                        val k = hz / 1000.0
                        if (abs(k - k.toInt()) < 0.01) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
                    } else "${hz.toInt()}"
                    drawIntoCanvas { canvas ->
                        axisPaint.textAlign = Paint.Align.CENTER
                        canvas.nativeCanvas.drawText(
                            label,
                            x,
                            plotBottom + with(density) { 15.dp.toPx() },
                            axisPaint
                        )
                    }
                }

                // 绘制每条频谱曲线（按叠放顺序）
                orderedSeries.forEachIndexed { idx, s ->
                    val spec = s.spectrum
                    val mags = spec.magnitudesDbfs
                    if (mags.size < 2) return@forEachIndexed
                    val startIdx = spec.localIndexForFrequency(options.minHz)
                    val endIdx = spec.localIndexForFrequency(options.maxHz)
                    if (endIdx <= startIdx) return@forEachIndexed
                    val refDb = refs[idx]

                    val linePath = Path()
                    val fillPath = Path()
                    var started = false
                    var firstX = plotLeft
                    var lastX = plotRight

                    val steps = plotW.toInt().coerceIn(180, 900)
                    var prevBin = startIdx
                    for (px in 0..steps) {
                        val frac = px.toDouble() / steps
                        val hz = fracToFreq(frac, options)
                        val bin = spec.localIndexForFrequency(hz)
                        val lo = min(prevBin, bin).coerceIn(startIdx, endIdx)
                        val hi = max(prevBin, bin).coerceIn(startIdx, endIdx)
                        var maxMag = Float.NEGATIVE_INFINITY
                        for (k in lo..hi) {
                            if (mags[k] > maxMag) maxMag = mags[k]
                        }
                        prevBin = bin
                        val dispDb = if (options.normalizeToPeak) maxMag.toDouble() - refDb else maxMag.toDouble()
                        val x = plotLeft + (plotW * frac).toFloat()
                        val y = yFor(dispDb)
                        if (!started) {
                            linePath.moveTo(x, y)
                            fillPath.moveTo(x, plotBottom)
                            fillPath.lineTo(x, y)
                            firstX = x
                            started = true
                        } else {
                            linePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                        lastX = x
                    }
                    if (!started) return@forEachIndexed
                    fillPath.lineTo(lastX, plotBottom)
                    fillPath.lineTo(firstX, plotBottom)
                    fillPath.close()

                    val isTop = s.isTopLayer || orderedSeries.size == 1
                    val curveAlpha = if (isTop) 1f else 0.70f
                    val strokeW = with(density) { (if (isTop) 2.2.dp else 1.5.dp).toPx() }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                s.color.copy(alpha = if (isTop) 0.22f else 0.10f),
                                Color.Transparent
                            ),
                            startY = plotTop,
                            endY = plotBottom
                        )
                    )
                    drawPath(
                        path = linePath,
                        color = s.color.copy(alpha = curveAlpha),
                        style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                // 绘制共振峰标记（单谱绘制完整气泡，双谱绘制彩色圆点与简明代号）
                val placedBoxes = mutableListOf<RectF>()
                val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = with(density) { 10.5.sp.toPx() }
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Surface2.copy(alpha = 0.92f).toArgb()
                }
                val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = with(density) { 1.dp.toPx() }
                }

                val multiSeries = orderedSeries.size > 1
                orderedSeries.forEachIndexed { sIdx, s ->
                    val refDb = refs[sIdx]
                    val visiblePeaks = s.peaks
                        .filter { it.frequencyHz in options.minHz..options.maxHz }
                        .sortedBy { it.frequencyHz }
                    for (peak in visiblePeaks) {
                        val x = xFor(peak.frequencyHz)
                        val localDb = s.spectrum.magnitudeAtFrequency(peak.frequencyHz)
                        val yDb = if (options.normalizeToPeak) localDb - refDb else localDb
                        val y = yFor(yDb)
                        val markerColor = if (!multiSeries) {
                            if (peak.isManualOverride) InfoBlue else Warm
                        } else s.color

                        if (!multiSeries) {
                            drawLine(
                                color = markerColor.copy(alpha = 0.55f),
                                start = Offset(x, y),
                                end = Offset(x, plotBottom),
                                strokeWidth = 1.5f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
                            )
                        }
                        drawCircle(
                            color = markerColor,
                            radius = with(density) { 4.dp.toPx() },
                            center = Offset(x, y)
                        )

                        val tag = if (multiSeries) {
                            "${peak.modalId.symbol} ${f1(peak.frequencyHz)}"
                        } else {
                            "${peak.modalId.symbol} ${f1(peak.frequencyHz)} Hz"
                        }
                        val tw = badgeTextPaint.measureText(tag)
                        val padH = with(density) { 5.dp.toPx() }
                        val bh = with(density) { 18.dp.toPx() }
                        val bw = tw + padH * 2
                        val bx = (x - bw / 2f).coerceIn(plotLeft + 2f, plotRight - bw - 2f)
                        var by = (y - bh - with(density) { 7.dp.toPx() }).coerceAtLeast(plotTop + 2f)

                        var tries = 0
                        while (tries < 6) {
                            val cand = RectF(bx, by, bx + bw, by + bh)
                            if (placedBoxes.none { RectF.intersects(it, cand) }) break
                            by += bh + with(density) { 3.dp.toPx() }
                            if (by + bh > plotBottom - 4f) {
                                by = plotTop + 2f + tries * with(density) { 6.dp.toPx() }
                            }
                            tries++
                        }
                        val box = RectF(bx, by, bx + bw, by + bh)
                        placedBoxes.add(box)

                        drawIntoCanvas { canvas ->
                            val nc = canvas.nativeCanvas
                            val corner = with(density) { 5.dp.toPx() }
                            nc.drawRoundRect(box, corner, corner, badgeBgPaint)
                            badgeBorderPaint.color = markerColor.toArgb()
                            nc.drawRoundRect(box, corner, corner, badgeBorderPaint)
                            badgeTextPaint.color = if (multiSeries) markerColor.toArgb() else TextMain.toArgb()
                            nc.drawText(tag, box.left + padH, box.centerY() + with(density) { 3.5.dp.toPx() }, badgeTextPaint)
                        }
                    }
                }

                // 触摸十字游标线
                val cHz = cursorFreqHz
                if (cHz != null && cHz in options.minHz..options.maxHz) {
                    val cx = xFor(cHz)
                    drawLine(
                        color = InfoBlue.copy(alpha = 0.85f),
                        start = Offset(cx, plotTop),
                        end = Offset(cx, plotBottom),
                        strokeWidth = 1.8f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                    )
                    orderedSeries.forEachIndexed { idx, s ->
                        val v = s.spectrum.magnitudeAtFrequency(cHz) - (if (options.normalizeToPeak) refs[idx] else 0.0)
                        val cy = yFor(v)
                        drawCircle(
                            color = s.color,
                            radius = with(density) { 4.5.dp.toPx() },
                            center = Offset(cx, cy)
                        )
                    }
                }

                // 坐标外框
                drawRect(
                    color = FretWire,
                    topLeft = Offset(plotLeft, plotTop),
                    size = androidx.compose.ui.geometry.Size(plotW, plotH),
                    style = Stroke(width = 1.2f)
                )
            }
        }
    }
}

private fun fracToFreq(frac: Double, options: SpectrumChartOptions): Double =
    if (options.logScale) {
        options.minHz * (options.maxHz / options.minHz).pow(frac)
    } else {
        options.minHz + (options.maxHz - options.minHz) * frac
    }

private fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)
