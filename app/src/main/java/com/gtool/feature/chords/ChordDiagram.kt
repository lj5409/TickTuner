package com.gtool.feature.chords

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.music.Chord
import com.gtool.core.music.PitchClass
import com.gtool.core.music.Tuning
import com.gtool.core.music.Voicing
import com.gtool.ui.theme.ChordBlue
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.RootRed
import com.gtool.ui.theme.TextMuted
import kotlin.math.min

private const val DIAGRAM_FRET_ROWS = 5

/**
 * 和弦图：0–5 品窗口，低音弦在左，横按画成长条，点上标手指编号。
 */
@Composable
fun ChordDiagram(
    tuning: Tuning,
    chord: Chord,
    capo: Int,
    voicing: Voicing,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val fingerLabels = remember(textMeasurer) {
        (1..4).associateWith { finger ->
            textMeasurer.measure(
                finger.toString(),
                TextStyle(
                    color = Color(0xFF08120E),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }

    Canvas(modifier) {
        val stringCount = min(tuning.strings.size, voicing.frets.size)
        if (stringCount <= 0) return@Canvas
        val left = 30.dp.toPx()
        val right = size.width - 16.dp.toPx()
        val top = 40.dp.toPx()
        val rowHeight = min(42.dp.toPx(), (size.height - top - 22.dp.toPx()) / DIAGRAM_FRET_ROWS)
        val bottom = top + rowHeight * DIAGRAM_FRET_ROWS
        val spacing = if (stringCount > 1) (right - left) / (stringCount - 1) else 0f
        val baseFret = voicing.baseFret
        val windowStart = if (baseFret <= 1) 1 else baseFret
        fun stringX(index: Int): Float = left + index * spacing
        fun fretY(fret: Int): Float = top + (fret - windowStart + 0.5f) * rowHeight

        // 品丝横线。
        for (row in 0..DIAGRAM_FRET_ROWS) {
            val y = top + row * rowHeight
            drawLine(
                color = FretWire,
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        // 琴枕（开放把位）加粗。
        if (baseFret <= 1) {
            drawLine(
                color = TextMuted.copy(alpha = 0.9f),
                start = Offset(left, top),
                end = Offset(right, top),
                strokeWidth = 3.5.dp.toPx()
            )
        } else {
            drawText(
                textMeasurer = textMeasurer,
                text = "${baseFret}品",
                topLeft = Offset(0f, top - 6.dp.toPx()),
                style = TextStyle(
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        // 琴弦竖线。
        for (stringIndex in 0 until stringCount) {
            val x = stringX(stringIndex)
            drawLine(
                color = TextMuted.copy(alpha = 0.55f),
                start = Offset(x, top),
                end = Offset(x, bottom),
                strokeWidth = (1.5f - stringIndex * 0.1f).coerceAtLeast(0.7f).dp.toPx()
            )
            val fret = voicing.fretAt(stringIndex)
            val markerY = top - 14.dp.toPx()
            if (fret < 0) {
                val cross = 3.5.dp.toPx()
                drawLine(
                    TextMuted,
                    Offset(x - cross, markerY - cross),
                    Offset(x + cross, markerY + cross),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    TextMuted,
                    Offset(x - cross, markerY + cross),
                    Offset(x + cross, markerY - cross),
                    strokeWidth = 1.5.dp.toPx()
                )
            } else if (fret == 0) {
                drawCircle(
                    color = TextMuted,
                    radius = 4.5.dp.toPx(),
                    center = Offset(x, markerY),
                    style = Stroke(width = 1.4.dp.toPx())
                )
            }
        }

        // 横按：同一手指压住多根弦时画成长条（最低把位通常是 1 指）。
        val barreShapes = (1..4).mapNotNull { finger ->
            val strings = (0 until stringCount).filter { stringIndex ->
                voicing.fingers.getOrElse(stringIndex) { 0 } == finger &&
                    voicing.frets.getOrElse(stringIndex) { -1 } > 0
            }
            val fret = strings.map { voicing.frets[it] }.distinct().singleOrNull()
            if (strings.size >= 2 && fret != null &&
                fret - windowStart in 0 until DIAGRAM_FRET_ROWS
            ) {
                BarreShape(finger, fret, strings)
            } else {
                null
            }
        }
        val barreStrings = barreShapes.flatMap { it.strings }.toSet()

        val dotRadius = min(spacing * 0.34f, 11.dp.toPx())
        for (shape in barreShapes) {
            val first = shape.strings.minOrNull() ?: continue
            val last = shape.strings.maxOrNull() ?: continue
            val x1 = stringX(first)
            val x2 = stringX(last)
            val y = fretY(shape.fret)
            val firstPc = PitchClass.of(tuning.strings[first].midi + capo + shape.fret)
            drawRoundRect(
                color = if (firstPc == chord.root) RootRed else ChordBlue,
                topLeft = Offset(x1 - dotRadius, y - dotRadius),
                size = Size(x2 - x1 + dotRadius * 2f, dotRadius * 2f),
                cornerRadius = CornerRadius(dotRadius, dotRadius)
            )
            val label = fingerLabels[shape.finger]
            if (label != null) {
                drawText(
                    label,
                    topLeft = Offset(
                        (x1 + x2) / 2f - label.size.width / 2f,
                        y - label.size.height / 2f
                    )
                )
            }
        }

        // 按弦点与手指编号。
        for (stringIndex in 0 until stringCount) {
            val fret = voicing.fretAt(stringIndex)
            if (fret <= 0) continue
            val row = fret - windowStart
            if (row !in 0 until DIAGRAM_FRET_ROWS) continue
            if (stringIndex in barreStrings) continue

            val pc = PitchClass.of(tuning.strings[stringIndex].midi + capo + fret)
            val x = stringX(stringIndex)
            val y = fretY(fret)
            drawCircle(
                color = if (pc == chord.root) RootRed else ChordBlue,
                radius = dotRadius,
                center = Offset(x, y)
            )
            val finger = voicing.fingers.getOrElse(stringIndex) { 0 }
            val label = fingerLabels[finger]
            if (label != null) {
                drawText(
                    label,
                    topLeft = Offset(
                        x - label.size.width / 2f,
                        y - label.size.height / 2f
                    )
                )
            }
        }
    }
}

private data class BarreShape(val finger: Int, val fret: Int, val strings: List<Int>)
