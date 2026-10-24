package com.gtool.feature.chords

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
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
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.ChordBlue
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.RootRed
import com.gtool.ui.theme.Surface3
import com.gtool.ui.theme.TextMuted
import kotlin.math.PI
import kotlin.math.sin

private const val FRET_COUNT = 15

/**
 * 6 弦 × 15 品指板（Canvas 绘制，低音弦在下）。
 *
 * - 显示当前调弦 + 变调夹下每个品位的音；根音红色、和弦音蓝色、非和弦音淡灰。
 * - 左侧显示弦号与空弦音；当前指法闷音的弦画 ×，空弦画圈。
 * - 当前指法的按品位置用 Accent 描边；播放时按 [stringEnergies] 做弦振动与涟漪。
 */
@Composable
fun Fretboard(
    tuning: Tuning,
    chord: Chord,
    capo: Int,
    useFlats: Boolean,
    voicing: Voicing?,
    stringEnergies: List<Animatable<Float, AnimationVector1D>>,
    modifier: Modifier = Modifier
) {
    val openColumn = 40.dp
    val gutter = 22.dp
    val fretWidth = 46.dp
    val topPadding = 26.dp
    val stringSpacing = 33.dp
    val bottomPadding = 30.dp
    val stringCount = tuning.strings.size
    val height = topPadding + stringSpacing * (stringCount - 1).coerceAtLeast(1) + bottomPadding
    val width = gutter + openColumn + fretWidth * FRET_COUNT + 18.dp

    val textMeasurer = rememberTextMeasurer()
    val texts = remember(textMeasurer, useFlats) {
        FretboardTexts.create(textMeasurer, useFlats)
    }
    val scrollState = rememberScrollState()

    Box(modifier.horizontalScroll(scrollState)) {
        Canvas(Modifier.width(width).height(height)) {
            val gutterPx = gutter.toPx()
            val openColumnPx = openColumn.toPx()
            val nutX = gutterPx + openColumnPx
            val fretWidthPx = fretWidth.toPx()
            val top = topPadding.toPx()
            val bottom = size.height - bottomPadding.toPx()
            val spacing = if (stringCount > 1) (bottom - top) / (stringCount - 1) else 0f
            fun stringY(index: Int): Float = bottom - index * spacing

            val gridEnd = nutX + fretWidthPx * FRET_COUNT

            // 品位标记（3/5/7/9/12/15）。
            val inlayY = (top + bottom) / 2f
            for (fret in listOf(3, 5, 7, 9, 12, 15)) {
                val x = nutX + (fret - 0.5f) * fretWidthPx
                drawCircle(Surface3.copy(alpha = 0.55f), radius = 4.dp.toPx(), center = Offset(x, inlayY))
            }

            // 品丝。
            for (fret in 1..FRET_COUNT) {
                val x = nutX + fret * fretWidthPx
                val isTwelfth = fret == 12
                drawLine(
                    color = if (isTwelfth) TextMuted.copy(alpha = 0.55f) else FretWire,
                    start = Offset(x, top - 10.dp.toPx()),
                    end = Offset(x, bottom + 10.dp.toPx()),
                    strokeWidth = (if (isTwelfth) 1.6.dp else 1.dp).toPx()
                )
            }
            // 琴枕/变调夹位置（相对品位 0）。
            drawLine(
                color = TextMuted.copy(alpha = 0.8f),
                start = Offset(nutX, top - 10.dp.toPx()),
                end = Offset(nutX, bottom + 10.dp.toPx()),
                strokeWidth = 3.dp.toPx()
            )

            // 品位数字。
            for (fret in listOf(3, 5, 7, 9, 12, 15)) {
                val layout = texts.fretNumbers[fret] ?: continue
                val x = nutX + (fret - 0.5f) * fretWidthPx
                drawText(
                    layout,
                    topLeft = Offset(
                        x - layout.size.width / 2f,
                        bottom + 12.dp.toPx()
                    )
                )
            }

            // 弦线与扫弦涟漪。
            for (stringIndex in 0 until stringCount) {
                val y = stringY(stringIndex)
                val openMidi = tuning.strings[stringIndex].midi + capo
                val energy = stringEnergies.getOrNull(stringIndex)?.value ?: 0f
                val thickness = (1.7f - stringIndex * 0.13f).coerceAtLeast(0.7f).dp.toPx()
                val baseColor = TextMuted.copy(alpha = 0.55f)

                if (energy > 0.02f) {
                    val path = Path()
                    val amplitude = energy * 4.5.dp.toPx()
                    val steps = 36
                    for (step in 0..steps) {
                        val fraction = step.toFloat() / steps
                        val x = nutX + (gridEnd - nutX) * fraction
                        val wave = sin(fraction * PI.toFloat() * 6f) * amplitude * (1f - fraction * 0.35f)
                        if (step == 0) path.moveTo(x, y + wave) else path.lineTo(x, y + wave)
                    }
                    drawPath(
                        path,
                        color = lerp(baseColor, Accent, energy.coerceIn(0f, 1f)),
                        style = Stroke(width = thickness)
                    )
                } else {
                    drawLine(
                        color = baseColor,
                        start = Offset(nutX, y),
                        end = Offset(gridEnd, y),
                        strokeWidth = thickness
                    )
                }

                // 所有品位的音点。
                val voicingFret = voicing?.fretAt(stringIndex) ?: -1
                for (fret in 1..FRET_COUNT) {
                    val pc = PitchClass.of(openMidi + fret)
                    val isRoot = pc == chord.root
                    val inChord = chord.contains(pc)
                    val x = nutX + (fret - 0.5f) * fretWidthPx
                    val center = Offset(x, y)
                    val isVoicingFret = voicingFret == fret

                    if (!inChord) {
                        drawCircle(
                            color = TextMuted.copy(alpha = 0.16f),
                            radius = 2.4.dp.toPx(),
                            center = center
                        )
                    } else {
                        val radius = if (isRoot) 10.dp else 8.dp
                        drawCircle(
                            color = if (isRoot) RootRed else ChordBlue,
                            radius = radius.toPx(),
                            center = center
                        )
                        val layout = texts.noteLabels[pc.semitone]
                        if (layout != null) {
                            drawText(
                                layout,
                                topLeft = Offset(
                                    x - layout.size.width / 2f,
                                    y - layout.size.height / 2f
                                )
                            )
                        }
                    }

                    if (isVoicingFret) {
                        drawCircle(
                            color = Accent.copy(alpha = 0.95f),
                            radius = (if (inChord) 13.dp else 8.dp).toPx(),
                            center = center,
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                    }
                }

                // 空弦列：音名 + 闷音 × / 空弦圈。
                val openPc = PitchClass.of(openMidi)
                val openX = nutX - openColumnPx / 2f
                if (voicingFret < 0 && voicing != null) {
                    val size = 4.dp.toPx()
                    drawLine(
                        TextMuted.copy(alpha = 0.8f),
                        Offset(openX - size, y - size),
                        Offset(openX + size, y + size),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        TextMuted.copy(alpha = 0.8f),
                        Offset(openX - size, y + size),
                        Offset(openX + size, y - size),
                        strokeWidth = 1.5.dp.toPx()
                    )
                } else {
                    val layout = when {
                        openPc == chord.root -> texts.openRootLabels[openPc.semitone]
                        chord.contains(openPc) -> texts.openChordLabels[openPc.semitone]
                        else -> texts.openOtherLabels[openPc.semitone]
                    }
                    if (layout != null) {
                        drawText(
                            layout,
                            topLeft = Offset(
                                openX - layout.size.width / 2f,
                                y - layout.size.height / 2f
                            )
                        )
                    }
                    if (voicingFret == 0) {
                        drawCircle(
                            color = Accent.copy(alpha = 0.9f),
                            radius = 11.dp.toPx(),
                            center = Offset(openX, y),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // 弦号。
                val numberLayout = texts.stringNumbers[stringCount - stringIndex]
                if (numberLayout != null) {
                    drawText(
                        numberLayout,
                        topLeft = Offset(
                            gutterPx / 2f - numberLayout.size.width / 2f,
                            y - numberLayout.size.height / 2f
                        )
                    )
                }

                // 拨弦涟漪（按品位置或空弦位置）。
                if (energy > 0.02f && voicingFret >= 0) {
                    val rippleX = if (voicingFret == 0) {
                        openX
                    } else {
                        nutX + (voicingFret - 0.5f) * fretWidthPx
                    }
                    val pulse = energy.coerceIn(0f, 1f)
                    drawCircle(
                        color = Accent.copy(alpha = 0.20f * pulse),
                        radius = (14.dp.toPx() + 16.dp.toPx() * (1f - pulse)),
                        center = Offset(rippleX, y)
                    )
                    drawCircle(
                        color = Accent.copy(alpha = 0.35f * pulse),
                        radius = (5.dp + 5.dp * pulse).toPx(),
                        center = Offset(rippleX, y)
                    )
                }
            }
        }
    }
}

private class FretboardTexts(
    val noteLabels: Map<Int, TextLayoutResult>,
    val openRootLabels: Map<Int, TextLayoutResult>,
    val openChordLabels: Map<Int, TextLayoutResult>,
    val openOtherLabels: Map<Int, TextLayoutResult>,
    val stringNumbers: Map<Int, TextLayoutResult>,
    val fretNumbers: Map<Int, TextLayoutResult>
) {
    companion object {
        fun create(measurer: TextMeasurer, useFlats: Boolean): FretboardTexts {
            val noteLabels = HashMap<Int, TextLayoutResult>()
            val openRoot = HashMap<Int, TextLayoutResult>()
            val openChord = HashMap<Int, TextLayoutResult>()
            val openOther = HashMap<Int, TextLayoutResult>()
            for (pc in PitchClass.entries) {
                val name = pc.name(useFlats)
                noteLabels[pc.semitone] = measurer.measure(
                    name,
                    TextStyle(color = Color(0xFF08120E), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                )
                openRoot[pc.semitone] = measurer.measure(
                    name,
                    TextStyle(color = RootRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                )
                openChord[pc.semitone] = measurer.measure(
                    name,
                    TextStyle(color = ChordBlue, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                )
                openOther[pc.semitone] = measurer.measure(
                    name,
                    TextStyle(color = TextMuted.copy(alpha = 0.55f), fontSize = 9.sp)
                )
            }
            val stringNumbers = HashMap<Int, TextLayoutResult>()
            for (number in 1..8) {
                stringNumbers[number] = measurer.measure(
                    number.toString(),
                    TextStyle(color = TextMuted.copy(alpha = 0.75f), fontSize = 9.sp)
                )
            }
            val fretNumbers = HashMap<Int, TextLayoutResult>()
            for (fret in listOf(3, 5, 7, 9, 12, 15)) {
                fretNumbers[fret] = measurer.measure(
                    fret.toString(),
                    TextStyle(color = TextMuted.copy(alpha = 0.7f), fontSize = 9.sp)
                )
            }
            return FretboardTexts(
                noteLabels = noteLabels,
                openRootLabels = openRoot,
                openChordLabels = openChord,
                openOtherLabels = openOther,
                stringNumbers = stringNumbers,
                fretNumbers = fretNumbers
            )
        }
    }
}
