package com.gtool.feature.chords

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
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
import com.gtool.core.music.VoicingGenerator
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.ChordBlue
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.RootRed
import com.gtool.ui.theme.Surface3
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val FRETBOARD_FRET_COUNT = VoicingGenerator.MAX_ABSOLUTE_FRET

/**
 * 6 弦 × 15 品绝对位置指板（Canvas 绘制，低音弦在下）。
 *
 * - 左侧固定列展示弦号与当前变调夹下的空弦音 / 闷音 × / 空弦圈，不随品位横向滚动。
 * - 右侧可滚动指板始终按吉他绝对品位（0–15 品）绘制：
 *   - 变调夹竖线画在第 [capo] 品品丝处，选定变调夹后自动滚动使变调夹竖线对齐最左端；
 *   - 变调夹左侧（1..capo 品）变暗显示，仍可向左滑回查看；
 *   - 变调夹右侧高亮当前和弦组成音（根音红、和弦音蓝、非和弦音淡灰）与推荐指法按品圈；
 *   - 播放时按 [stringEnergies] 在变调夹右侧做弦振动与拨弦涟漪。
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
    val safeCapo = capo.coerceIn(0, FRETBOARD_FRET_COUNT - 1)
    val gutter = 22.dp
    val openColumn = 38.dp
    val pinnedWidth = gutter + openColumn
    val fretWidth = 46.dp
    val leftEdgePad = 2.dp
    val topPadding = 26.dp
    val stringSpacing = 33.dp
    val bottomPadding = 30.dp
    val stringCount = tuning.strings.size
    val height = topPadding + stringSpacing * (stringCount - 1).coerceAtLeast(1) + bottomPadding

    val textMeasurer = rememberTextMeasurer()
    val texts = remember(textMeasurer, useFlats) {
        FretboardTexts.create(textMeasurer, useFlats)
    }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current

    Row(modifier = modifier.fillMaxWidth()) {
        // 左侧固定列：弦号 + 当前有效空弦音 / 闷音 × / 空弦圈。
        Canvas(
            modifier = Modifier
                .width(pinnedWidth)
                .height(height)
        ) {
            val gutterPx = gutter.toPx()
            val openColumnPx = openColumn.toPx()
            val openX = gutterPx + openColumnPx / 2f
            val top = topPadding.toPx()
            val bottom = size.height - bottomPadding.toPx()
            val spacing = if (stringCount > 1) (bottom - top) / (stringCount - 1) else 0f
            fun stringY(index: Int): Float = bottom - index * spacing

            for (stringIndex in 0 until stringCount) {
                val y = stringY(stringIndex)
                val effectiveOpenMidi = tuning.strings[stringIndex].midi + safeCapo
                val openPc = PitchClass.of(effectiveOpenMidi)
                val voicingRelFret = voicing?.fretAt(stringIndex) ?: -1
                val energy = stringEnergies.getOrNull(stringIndex)?.value ?: 0f

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

                // 空弦列：闷音 × 或 有效空弦音名 + 空弦圈。
                if (voicingRelFret < 0 && voicing != null) {
                    val cross = 4.dp.toPx()
                    drawLine(
                        TextMuted.copy(alpha = 0.8f),
                        Offset(openX - cross, y - cross),
                        Offset(openX + cross, y + cross),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        TextMuted.copy(alpha = 0.8f),
                        Offset(openX - cross, y + cross),
                        Offset(openX + cross, y - cross),
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
                    if (voicingRelFret == 0) {
                        drawCircle(
                            color = Accent.copy(alpha = 0.9f),
                            radius = 11.dp.toPx(),
                            center = Offset(openX, y),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // 空弦拨弦涟漪。
                if (energy > 0.02f && voicingRelFret == 0) {
                    val pulse = energy.coerceIn(0f, 1f)
                    drawCircle(
                        color = Accent.copy(alpha = 0.20f * pulse),
                        radius = (14.dp.toPx() + 14.dp.toPx() * (1f - pulse)),
                        center = Offset(openX, y)
                    )
                    drawCircle(
                        color = Accent.copy(alpha = 0.35f * pulse),
                        radius = (5.dp + 5.dp * pulse).toPx(),
                        center = Offset(openX, y)
                    )
                }
            }
        }

        // 右侧可横向滚动绝对品位指板（0–15 品）。
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val viewportWidth = maxWidth
            val minRightPad = 18.dp
            val fretsRightOfCapo = (FRETBOARD_FRET_COUNT - safeCapo).coerceAtLeast(1)
            val rightPad = if (safeCapo > 0) {
                (viewportWidth - fretWidth * fretsRightOfCapo - leftEdgePad).coerceAtLeast(minRightPad)
            } else {
                minRightPad
            }
            val canvasWidth = leftEdgePad + fretWidth * FRETBOARD_FRET_COUNT + rightPad

            var initialized by remember { mutableStateOf(false) }
            LaunchedEffect(safeCapo, rightPad) {
                val targetPx = with(density) { (fretWidth * safeCapo).toPx() }.roundToInt()
                if (!initialized) {
                    initialized = true
                    scrollState.scrollTo(targetPx)
                } else {
                    scrollState.animateScrollTo(targetPx)
                }
            }

            Box(Modifier.horizontalScroll(scrollState)) {
                Canvas(
                    modifier = Modifier
                        .width(canvasWidth)
                        .height(height)
                ) {
                    val nutX = leftEdgePad.toPx()
                    val fretWidthPx = fretWidth.toPx()
                    val capoX = nutX + safeCapo * fretWidthPx
                    val gridEnd = nutX + FRETBOARD_FRET_COUNT * fretWidthPx
                    val top = topPadding.toPx()
                    val bottom = size.height - bottomPadding.toPx()
                    val spacing = if (stringCount > 1) (bottom - top) / (stringCount - 1) else 0f
                    fun stringY(index: Int): Float = bottom - index * spacing

                    // 品位标记点（绝对 3/5/7/9/12/15 品）。
                    val inlayY = (top + bottom) / 2f
                    for (absFret in MAJOR_FRETS) {
                        val x = nutX + (absFret - 0.5f) * fretWidthPx
                        val behindCapo = absFret <= safeCapo
                        drawCircle(
                            color = Surface3.copy(alpha = if (behindCapo) 0.24f else 0.55f),
                            radius = 4.dp.toPx(),
                            center = Offset(x, inlayY)
                        )
                    }

                    // 绝对品丝（1..15 品）。
                    for (absFret in 1..FRETBOARD_FRET_COUNT) {
                        if (absFret == safeCapo) continue
                        val x = nutX + absFret * fretWidthPx
                        val isTwelfth = absFret == 12
                        val behindCapo = absFret < safeCapo
                        val wireColor = when {
                            behindCapo -> FretWire.copy(alpha = 0.45f)
                            isTwelfth -> TextMuted.copy(alpha = 0.55f)
                            else -> FretWire
                        }
                        drawLine(
                            color = wireColor,
                            start = Offset(x, top - 10.dp.toPx()),
                            end = Offset(x, bottom + 10.dp.toPx()),
                            strokeWidth = (if (isTwelfth) 1.6.dp else 1.dp).toPx()
                        )
                    }

                    // 0 品琴枕。
                    drawLine(
                        color = TextMuted.copy(alpha = if (safeCapo == 0) 0.85f else 0.30f),
                        start = Offset(nutX, top - 10.dp.toPx()),
                        end = Offset(nutX, bottom + 10.dp.toPx()),
                        strokeWidth = (if (safeCapo == 0) 3.dp else 2.dp).toPx()
                    )

                    // 变调夹竖线（高亮在绝对 capo 品丝处）及顶部小标签。
                    if (safeCapo > 0) {
                        drawLine(
                            color = Warm,
                            start = Offset(capoX, top - 11.dp.toPx()),
                            end = Offset(capoX, bottom + 11.dp.toPx()),
                            strokeWidth = 3.5.dp.toPx()
                        )
                        val capoLabel = texts.capoLabels[safeCapo]
                        if (capoLabel != null) {
                            drawText(
                                capoLabel,
                                topLeft = Offset(capoX + 4.dp.toPx(), 2.dp.toPx())
                            )
                        }
                    }

                    // 绝对品位数字（1..15，标记品位高亮，变调夹左侧变暗）。
                    for (absFret in 1..FRETBOARD_FRET_COUNT) {
                        val behindCapo = absFret <= safeCapo
                        val layout = when {
                            behindCapo -> texts.dimmedFretNumbers[absFret]
                            absFret in MAJOR_FRETS -> texts.majorFretNumbers[absFret]
                            else -> texts.minorFretNumbers[absFret]
                        } ?: continue
                        val x = nutX + (absFret - 0.5f) * fretWidthPx
                        drawText(
                            layout,
                            topLeft = Offset(
                                x - layout.size.width / 2f,
                                bottom + 12.dp.toPx()
                            )
                        )
                    }

                    // 弦线、绝对品位音点与拨弦涟漪。
                    for (stringIndex in 0 until stringCount) {
                        val y = stringY(stringIndex)
                        val absOpenMidi = tuning.strings[stringIndex].midi
                        val energy = stringEnergies.getOrNull(stringIndex)?.value ?: 0f
                        val thickness = (1.7f - stringIndex * 0.13f).coerceAtLeast(0.7f).dp.toPx()
                        val activeBaseColor = TextMuted.copy(alpha = 0.55f)

                        // 变调夹左侧弦线（不振动、变暗）。
                        if (safeCapo > 0) {
                            drawLine(
                                color = TextMuted.copy(alpha = 0.22f),
                                start = Offset(nutX, y),
                                end = Offset(capoX, y),
                                strokeWidth = thickness
                            )
                        }

                        // 变调夹右侧有效发声段弦线与振动波。
                        if (energy > 0.02f) {
                            val path = Path()
                            val amplitude = energy * 4.5.dp.toPx()
                            val steps = 36
                            for (step in 0..steps) {
                                val fraction = step.toFloat() / steps
                                val x = capoX + (gridEnd - capoX) * fraction
                                val wave = sin(fraction * PI.toFloat() * 6f) * amplitude * (1f - fraction * 0.35f)
                                if (step == 0) path.moveTo(x, y + wave) else path.lineTo(x, y + wave)
                            }
                            drawPath(
                                path,
                                color = lerp(activeBaseColor, Accent, energy.coerceIn(0f, 1f)),
                                style = Stroke(width = thickness)
                            )
                        } else {
                            drawLine(
                                color = activeBaseColor,
                                start = Offset(capoX, y),
                                end = Offset(gridEnd, y),
                                strokeWidth = thickness
                            )
                        }

                        // 1..15 绝对品位音点。
                        val voicingRelFret = voicing?.fretAt(stringIndex) ?: -1
                        val voicingAbsFret = if (voicingRelFret > 0) safeCapo + voicingRelFret else -1

                        for (absFret in 1..FRETBOARD_FRET_COUNT) {
                            val pc = PitchClass.of(absOpenMidi + absFret)
                            val isRoot = pc == chord.root
                            val inChord = chord.contains(pc)
                            val behindCapo = absFret <= safeCapo
                            val x = nutX + (absFret - 0.5f) * fretWidthPx
                            val center = Offset(x, y)

                            if (!inChord) {
                                drawCircle(
                                    color = TextMuted.copy(alpha = if (behindCapo) 0.08f else 0.16f),
                                    radius = 2.4.dp.toPx(),
                                    center = center
                                )
                            } else if (behindCapo) {
                                val radius = if (isRoot) 9.dp else 7.5.dp
                                drawCircle(
                                    color = (if (isRoot) RootRed else ChordBlue).copy(alpha = 0.22f),
                                    radius = radius.toPx(),
                                    center = center
                                )
                                val layout = texts.dimmedNoteLabels[pc.semitone]
                                if (layout != null) {
                                    drawText(
                                        layout,
                                        topLeft = Offset(
                                            x - layout.size.width / 2f,
                                            y - layout.size.height / 2f
                                        )
                                    )
                                }
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

                            if (!behindCapo && absFret == voicingAbsFret) {
                                drawCircle(
                                    color = Accent.copy(alpha = 0.95f),
                                    radius = (if (inChord) 13.dp else 8.dp).toPx(),
                                    center = center,
                                    style = Stroke(width = 1.6.dp.toPx())
                                )
                            }
                        }

                        // 按品位置的拨弦涟漪。
                        if (energy > 0.02f && voicingAbsFret in (safeCapo + 1)..FRETBOARD_FRET_COUNT) {
                            val rippleX = nutX + (voicingAbsFret - 0.5f) * fretWidthPx
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
    }
}

private val MAJOR_FRETS = setOf(3, 5, 7, 9, 12, 15)

private class FretboardTexts(
    val noteLabels: Map<Int, TextLayoutResult>,
    val dimmedNoteLabels: Map<Int, TextLayoutResult>,
    val openRootLabels: Map<Int, TextLayoutResult>,
    val openChordLabels: Map<Int, TextLayoutResult>,
    val openOtherLabels: Map<Int, TextLayoutResult>,
    val stringNumbers: Map<Int, TextLayoutResult>,
    val majorFretNumbers: Map<Int, TextLayoutResult>,
    val minorFretNumbers: Map<Int, TextLayoutResult>,
    val dimmedFretNumbers: Map<Int, TextLayoutResult>,
    val capoLabels: Map<Int, TextLayoutResult>
) {
    companion object {
        fun create(measurer: TextMeasurer, useFlats: Boolean): FretboardTexts {
            val noteLabels = HashMap<Int, TextLayoutResult>()
            val dimmedNoteLabels = HashMap<Int, TextLayoutResult>()
            val openRoot = HashMap<Int, TextLayoutResult>()
            val openChord = HashMap<Int, TextLayoutResult>()
            val openOther = HashMap<Int, TextLayoutResult>()
            for (pc in PitchClass.entries) {
                val name = pc.name(useFlats)
                noteLabels[pc.semitone] = measurer.measure(
                    name,
                    TextStyle(color = Color(0xFF08120E), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                )
                dimmedNoteLabels[pc.semitone] = measurer.measure(
                    name,
                    TextStyle(color = TextMuted.copy(alpha = 0.45f), fontSize = 8.5.sp, fontWeight = FontWeight.Medium)
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
            val majorFretNumbers = HashMap<Int, TextLayoutResult>()
            val minorFretNumbers = HashMap<Int, TextLayoutResult>()
            val dimmedFretNumbers = HashMap<Int, TextLayoutResult>()
            for (fret in 1..FRETBOARD_FRET_COUNT) {
                majorFretNumbers[fret] = measurer.measure(
                    fret.toString(),
                    TextStyle(color = TextMuted.copy(alpha = 0.85f), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                )
                minorFretNumbers[fret] = measurer.measure(
                    fret.toString(),
                    TextStyle(color = TextMuted.copy(alpha = 0.42f), fontSize = 9.sp)
                )
                dimmedFretNumbers[fret] = measurer.measure(
                    fret.toString(),
                    TextStyle(color = TextMuted.copy(alpha = 0.25f), fontSize = 9.sp)
                )
            }
            val capoLabels = HashMap<Int, TextLayoutResult>()
            for (capo in 1 until FRETBOARD_FRET_COUNT) {
                capoLabels[capo] = measurer.measure(
                    "Capo $capo",
                    TextStyle(color = Warm, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                )
            }
            return FretboardTexts(
                noteLabels = noteLabels,
                dimmedNoteLabels = dimmedNoteLabels,
                openRootLabels = openRoot,
                openChordLabels = openChord,
                openOtherLabels = openOther,
                stringNumbers = stringNumbers,
                majorFretNumbers = majorFretNumbers,
                minorFretNumbers = minorFretNumbers,
                dimmedFretNumbers = dimmedFretNumbers,
                capoLabels = capoLabels
            )
        }
    }
}
