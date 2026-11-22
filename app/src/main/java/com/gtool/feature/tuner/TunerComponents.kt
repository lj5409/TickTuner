package com.gtool.feature.tuner

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.music.Note
import com.gtool.core.music.Tuning
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Danger
import com.gtool.ui.theme.InfoBlue
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.Surface3
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------------ 文案/数值

internal fun formatOneDecimal(value: Double): String =
    String.format(Locale.US, "%.1f", value)

internal fun formatCents(cents: Double?): String {
    if (cents == null) return "—"
    val rounded = cents.roundToInt()
    val sign = if (rounded > 0) "+" else ""
    return "$sign$rounded ¢"
}

internal fun tuningStatusText(state: TunerUiState): String {
    val cents = state.cents
    if (state.detectedNote == null || cents == null) return "—"
    return when {
        abs(cents) <= TunerViewModel.IN_TUNE_CENTS -> "准"
        cents >= 600.0 -> "高过八度 · 大幅放松"
        cents <= -600.0 -> "低过八度 · 大幅拧紧"
        cents > 0 -> "偏高 · 松一点"
        else -> "偏低 · 紧一点"
    }
}

// ------------------------------------------------------------------ 小徽章/按钮

@Composable
fun TunerChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) Accent.copy(alpha = 0.22f) else Surface2,
        contentColor = if (selected) Accent else TextMain,
        modifier = modifier
    ) {
        Text(
            text = text,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

// ------------------------------------------------------------------ 半圆表盘

/**
 * -50..+50 音分的半圆表盘，指针位置平滑动画；±3 音分安全区始终标绿。
 * 当 [fillAvailableHeight] 为 true 时，Canvas 自动填满分配到的剩余高度（用于横屏 / 悬停 / 展开大屏）。
 */
@Composable
fun CentsGauge(
    cents: Float?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    fillAvailableHeight: Boolean = false
) {
    val target = (cents ?: 0f).coerceIn(-50f, 50f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 120),
        label = "tunerNeedle"
    )
    val inTune = enabled && cents != null && abs(cents) <= 3f
    val needleColor = when {
        !enabled || cents == null -> TextMuted.copy(alpha = 0.35f)
        inTune -> Accent
        animated > 0f -> Warm
        else -> InfoBlue
    }

    if (fillAvailableHeight) {
        BoxWithConstraints(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            val labelRowHeight = 18.dp
            val availableCanvasHeight = (maxHeight - labelRowHeight).coerceAtLeast(48.dp)
            val radiusDp = min(maxWidth.value * 0.44f, availableCanvasHeight.value * 0.82f)
                .coerceAtLeast(24f).dp
            val fittedCanvasHeight = (radiusDp / 0.82f).coerceAtMost(availableCanvasHeight)
            val labelRowWidth = (radiusDp * 2.16f).coerceAtMost(maxWidth)

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CentsGaugeCanvas(
                    animated = animated,
                    inTune = inTune,
                    needleColor = needleColor,
                    proportionalStroke = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(fittedCanvasHeight)
                )
                Row(
                    modifier = Modifier
                        .width(labelRowWidth)
                        .height(labelRowHeight),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("-50", "-25", "0", "+25", "+50").forEach { label ->
                        Text(text = label, fontSize = 11.sp, color = TextMuted.copy(alpha = 0.7f))
                    }
                }
            }
        }
        return
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CentsGaugeCanvas(
            animated = animated,
            inTune = inTune,
            needleColor = needleColor,
            proportionalStroke = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("-50", "-25", "0", "+25", "+50").forEach { label ->
                Text(text = label, fontSize = 11.sp, color = TextMuted.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun CentsGaugeCanvas(
    animated: Float,
    inTune: Boolean,
    needleColor: Color,
    proportionalStroke: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height * 0.9f
        val radius = min(size.width * 0.44f, size.height * 0.82f).coerceAtLeast(20f)
        val strokeWidth = if (proportionalStroke) {
            (radius * 0.11f).coerceIn(10.dp.toPx(), 18.dp.toPx())
        } else {
            14.dp.toPx()
        }
        val arcTopLeft = Offset(centerX - radius, centerY - radius)
        val arcSize = Size(radius * 2f, radius * 2f)

        // 底轨
        drawArc(
            color = Surface3,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // ±3 音分安全区（-50..+50 映射到 180°..360°）
        val zoneStart = 180f + (47f / 100f) * 180f
        val zoneSweep = (6f / 100f) * 180f
        drawArc(
            color = Accent.copy(alpha = if (inTune) 0.95f else 0.4f),
            startAngle = zoneStart,
            sweepAngle = zoneSweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // 刻度
        val majorLen = (radius * 0.095f).coerceIn(7.dp.toPx(), 13.dp.toPx())
        val minorLen = majorLen * 0.55f
        for (value in -50..50 step 5) {
            val angle = Math.toRadians((180f + (value + 50) / 100f * 180f).toDouble())
            val cosAngle = cos(angle).toFloat()
            val sinAngle = sin(angle).toFloat()
            val major = value % 10 == 0
            val outer = radius - strokeWidth / 2f - 4.dp.toPx()
            val inner = outer - (if (major) majorLen else minorLen)
            drawLine(
                color = if (major) TextMuted.copy(alpha = 0.8f) else TextMuted.copy(alpha = 0.35f),
                start = Offset(centerX + cosAngle * inner, centerY + sinAngle * inner),
                end = Offset(centerX + cosAngle * outer, centerY + sinAngle * outer),
                strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 指针
        val needleAngle = Math.toRadians((180f + (animated + 50f) / 100f * 180f).toDouble())
        val cosNeedle = cos(needleAngle).toFloat()
        val sinNeedle = sin(needleAngle).toFloat()
        val startRadius = radius * 0.12f
        val endRadius = radius * 0.88f
        val needleStroke = (radius * 0.034f).coerceIn(3.dp.toPx(), 5.5.dp.toPx())
        drawLine(
            color = needleColor,
            start = Offset(centerX + cosNeedle * startRadius, centerY + sinNeedle * startRadius),
            end = Offset(centerX + cosNeedle * endRadius, centerY + sinNeedle * endRadius),
            strokeWidth = needleStroke,
            cap = StrokeCap.Round
        )

        // 转轴
        val pivotRadius = (radius * 0.075f).coerceIn(7.dp.toPx(), 11.dp.toPx())
        drawCircle(color = Surface2, radius = pivotRadius, center = Offset(centerX, centerY))
        drawCircle(
            color = needleColor,
            radius = pivotRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 2.5.dp.toPx())
        )
    }
}

// ------------------------------------------------------------------ 中央读数

@Composable
fun PitchPanel(
    state: TunerUiState,
    hasPermission: Boolean,
    onPlayTarget: (Int) -> Unit,
    onStopReference: () -> Unit,
    modifier: Modifier = Modifier,
    fillAvailableHeight: Boolean = false,
    showTuningNameInHint: Boolean = false,
    onRequestPermission: (() -> Unit)? = null
) {
    val note = state.detectedNote
    val bigColor = when {
        note == null -> TextMuted
        state.inTune -> Accent
        else -> TextMain
    }
    val hint = when {
        !hasPermission -> "需要麦克风权限"
        !state.listening -> "已暂停"
        note == null && showTuningNameInHint -> "${state.tuning.name} · 拨弦调音"
        note == null -> "拨动琴弦开始调音"
        showTuningNameInHint -> state.tuning.name
        else -> "当前音高"
    }

    if (!fillAvailableHeight) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Surface1,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = hint,
                    fontSize = 13.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = note?.pitchClass?.name(state.useFlats) ?: "—",
                        fontSize = 82.sp,
                        lineHeight = 86.sp,
                        fontWeight = FontWeight.Bold,
                        color = bigColor
                    )
                    if (note != null) {
                        Text(
                            text = note.octave.toString(),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = bigColor.copy(alpha = 0.75f),
                            modifier = Modifier.padding(bottom = 12.dp, start = 2.dp)
                        )
                    }
                }

                Text(
                    text = state.frequency?.let {
                        "${formatOneDecimal(it)} Hz · 清晰度 ${(state.clarity * 100).roundToInt()}%"
                    } ?: "— Hz",
                    fontSize = 14.sp,
                    color = TextMuted
                )

                Spacer(Modifier.height(2.dp))

                CentsGauge(
                    cents = state.cents?.toFloat(),
                    enabled = state.frequency != null,
                    modifier = Modifier.fillMaxWidth()
                )

                CentsStatusRow(state = state, centsFontSp = 28, statusFontSp = 16)

                Spacer(Modifier.height(4.dp))
                TargetInfoRow(
                    state = state,
                    onPlayTarget = onPlayTarget,
                    onStopReference = onStopReference,
                    minHeightDp = 40
                )
            }
        }
        return
    }

    // 自适应填满高度模式（横屏 / 半折叠上半屏 / 展开大屏左栏）
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Surface1,
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            val h = maxHeight
            val ultraCompact = h < 275.dp
            val compact = h < 365.dp
            val noteSp = when {
                ultraCompact -> 46
                compact -> 56
                h < 470.dp -> 70
                else -> 82
            }
            val octaveSp = when {
                ultraCompact -> 20
                compact -> 24
                h < 470.dp -> 30
                else -> 34
            }
            val centsSp = when {
                ultraCompact -> 20
                compact -> 24
                else -> 28
            }
            val statusSp = if (compact) 14 else 16

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (ultraCompact) {
                    // 极矮横屏：音名与频率/状态并排，最大化表盘高度
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = note?.pitchClass?.name(state.useFlats) ?: "—",
                                fontSize = noteSp.sp,
                                lineHeight = (noteSp + 2).sp,
                                fontWeight = FontWeight.Bold,
                                color = bigColor
                            )
                            if (note != null) {
                                Text(
                                    text = note.octave.toString(),
                                    fontSize = octaveSp.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = bigColor.copy(alpha = 0.75f),
                                    modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = hint,
                                fontSize = 11.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = state.frequency?.let {
                                    "${formatOneDecimal(it)} Hz · ${(state.clarity * 100).roundToInt()}%"
                                } ?: "— Hz",
                                fontSize = 12.sp,
                                color = TextMuted,
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    Text(
                        text = hint,
                        fontSize = if (compact) 12.sp else 13.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = note?.pitchClass?.name(state.useFlats) ?: "—",
                            fontSize = noteSp.sp,
                            lineHeight = (noteSp + 4).sp,
                            fontWeight = FontWeight.Bold,
                            color = bigColor
                        )
                        if (note != null) {
                            Text(
                                text = note.octave.toString(),
                                fontSize = octaveSp.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = bigColor.copy(alpha = 0.75f),
                                modifier = Modifier.padding(
                                    bottom = if (compact) 8.dp else 12.dp,
                                    start = 2.dp
                                )
                            )
                        }
                    }

                    Text(
                        text = state.frequency?.let {
                            "${formatOneDecimal(it)} Hz · 清晰度 ${(state.clarity * 100).roundToInt()}%"
                        } ?: "— Hz",
                        fontSize = if (compact) 12.sp else 14.sp,
                        color = TextMuted,
                        maxLines = 1
                    )
                }

                if (!hasPermission && onRequestPermission != null) {
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onRequestPermission,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("授予麦克风权限", fontSize = 13.sp)
                    }
                }

                CentsGauge(
                    cents = state.cents?.toFloat(),
                    enabled = state.frequency != null,
                    fillAvailableHeight = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = if (compact) 2.dp else 4.dp)
                )

                CentsStatusRow(state = state, centsFontSp = centsSp, statusFontSp = statusSp)

                TargetInfoRow(
                    state = state,
                    onPlayTarget = onPlayTarget,
                    onStopReference = onStopReference,
                    minHeightDp = if (compact) 32 else 38
                )
            }
        }
    }
}

@Composable
private fun CentsStatusRow(
    state: TunerUiState,
    centsFontSp: Int,
    statusFontSp: Int
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        val centsColor = when {
            state.cents == null -> TextMuted
            state.inTune -> Accent
            state.cents > 0 -> Warm
            else -> InfoBlue
        }
        Text(
            text = formatCents(state.cents),
            fontSize = centsFontSp.sp,
            fontWeight = FontWeight.Bold,
            color = centsColor,
            maxLines = 1
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = tuningStatusText(state),
            fontSize = statusFontSp.sp,
            fontWeight = if (state.inTune) FontWeight.SemiBold else FontWeight.Normal,
            color = if (state.inTune) Accent else TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TargetInfoRow(
    state: TunerUiState,
    onPlayTarget: (Int) -> Unit,
    onStopReference: () -> Unit,
    minHeightDp: Int
) {
    val targetIndex = state.targetIndex
    val target = targetIndex?.let { state.tuning.strings.getOrNull(it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeightDp.dp)
    ) {
        if (targetIndex != null && target != null) {
            val isPlayingTarget = state.playingIndex == targetIndex
            Text(
                text = "目标 ${target.name(state.useFlats)} · 第${state.tuning.stringNumber(targetIndex)}弦" +
                    if (state.lockedIndex == targetIndex) " · 已锁定" else "",
                fontSize = 13.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            TextButton(
                onClick = {
                    if (isPlayingTarget) onStopReference() else onPlayTarget(targetIndex)
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                if (isPlayingTarget) {
                    StopSquareIcon(color = Danger, sizeDp = 12)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "停止",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Danger,
                        maxLines = 1
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Accent
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "参考音",
                        fontSize = 13.sp,
                        color = Accent,
                        maxLines = 1
                    )
                }
            }
        } else {
            Text(
                text = "目标 — · 拨弦自动识别",
                fontSize = 13.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ------------------------------------------------------------------ 弦位列表（竖屏 / 展开右栏）

@Composable
fun StringsSection(
    state: TunerUiState,
    onToggleLock: (Int) -> Unit,
    onPlayReference: (Int) -> Unit,
    onStopReference: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("弦位", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
            Text(
                text = state.lockedIndex?.let {
                    "已锁定第${state.tuning.stringNumber(it)}弦 · 再次轻点取消"
                } ?: "轻点弦位可锁定目标",
                fontSize = 12.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        state.tuning.strings.forEachIndexed { index, note ->
            StringListRow(
                state = state,
                index = index,
                note = note,
                onToggleLock = onToggleLock,
                onPlayReference = onPlayReference,
                onStopReference = onStopReference
            )
        }
    }
}

@Composable
private fun StringListRow(
    state: TunerUiState,
    index: Int,
    note: Note,
    onToggleLock: (Int) -> Unit,
    onPlayReference: (Int) -> Unit,
    onStopReference: () -> Unit
) {
    val isTarget = state.targetIndex == index
    val isLocked = state.lockedIndex == index
    val isPlaying = state.playingIndex == index
    val displayCents = if (isTarget && state.cents != null) {
        state.cents
    } else {
        state.lastStringCents[index]
    }
    val stringInTune = displayCents != null && abs(displayCents) <= TunerViewModel.IN_TUNE_CENTS
    val isHit = isTarget && state.inTune
    val number = state.tuning.stringNumber(index)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = when {
            isHit -> Accent.copy(alpha = 0.16f)
            isLocked -> Surface2
            isTarget -> Surface2.copy(alpha = 0.65f)
            else -> Surface1
        },
        border = when {
            isPlaying -> BorderStroke(1.dp, Danger.copy(alpha = 0.65f))
            isLocked || isHit -> BorderStroke(1.dp, Accent.copy(alpha = 0.7f))
            else -> null
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleLock(index) }
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    isHit -> Accent
                    isLocked -> Accent.copy(alpha = 0.25f)
                    else -> Surface3
                },
                modifier = Modifier.size(36.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            if (isPlaying) onStopReference() else onPlayReference(index)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = number.toString(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isHit) Color(0xFF04211A) else TextMain
                    )
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.name(state.useFlats),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isTarget || isLocked || stringInTune) TextMain else TextMuted,
                    maxLines = 1
                )
                if (isLocked) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "已锁定",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Accent,
                        maxLines = 1
                    )
                }
            }

            Text(
                text = formatCents(displayCents),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    displayCents == null -> TextMuted.copy(alpha = 0.55f)
                    stringInTune -> Accent
                    displayCents > 0 -> Warm
                    else -> InfoBlue
                },
                maxLines = 1
            )

            Spacer(Modifier.width(4.dp))

            if (isPlaying) {
                Surface(
                    onClick = onStopReference,
                    shape = RoundedCornerShape(10.dp),
                    color = Danger.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, Danger.copy(alpha = 0.55f)),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        StopSquareIcon(color = Danger, sizeDp = 10)
                        Text(
                            text = "停止",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Danger,
                            maxLines = 1
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = { onPlayReference(index) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "循环播放${note.name(state.useFlats)}参考音",
                        modifier = Modifier.size(20.dp),
                        tint = TextMuted
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 两列满屏弦位网格（横屏 / 悬停下半屏）

/**
 * 两列满屏弦位网格（不滚动）：左列低音弦（6/5/4），右列高音弦（3/2/1），
 * 每张弦位卡片均分可用高度，大触控目标方便手持吉他时单手点选锁定或播参考音。
 */
@Composable
fun StringsTwoColumnGrid(
    state: TunerUiState,
    onToggleLock: (Int) -> Unit,
    onPlayReference: (Int) -> Unit,
    onStopReference: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = state.tuning.strings
    val rowsPerColumn = ((strings.size + 1) / 2).coerceAtLeast(1)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 左列：低音弦（如 6、5、4 弦）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (row in 0 until rowsPerColumn) {
                val index = row
                if (index < strings.size) {
                    StringGridTile(
                        state = state,
                        index = index,
                        note = strings[index],
                        onToggleLock = onToggleLock,
                        onPlayReference = onPlayReference,
                        onStopReference = onStopReference,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // 右列：高音弦（如 3、2、1 弦）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (row in 0 until rowsPerColumn) {
                val index = rowsPerColumn + row
                if (index < strings.size) {
                    StringGridTile(
                        state = state,
                        index = index,
                        note = strings[index],
                        onToggleLock = onToggleLock,
                        onPlayReference = onPlayReference,
                        onStopReference = onStopReference,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StringGridTile(
    state: TunerUiState,
    index: Int,
    note: Note,
    onToggleLock: (Int) -> Unit,
    onPlayReference: (Int) -> Unit,
    onStopReference: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isTarget = state.targetIndex == index
    val isLocked = state.lockedIndex == index
    val isPlaying = state.playingIndex == index
    val displayCents = if (isTarget && state.cents != null) {
        state.cents
    } else {
        state.lastStringCents[index]
    }
    val stringInTune = displayCents != null && abs(displayCents) <= TunerViewModel.IN_TUNE_CENTS
    val isHit = isTarget && state.inTune
    val number = state.tuning.stringNumber(index)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when {
            isHit -> Accent.copy(alpha = 0.18f)
            isLocked -> Surface2
            isTarget -> Surface2.copy(alpha = 0.7f)
            else -> Surface1
        },
        border = when {
            isPlaying -> BorderStroke(1.2.dp, Danger.copy(alpha = 0.7f))
            isLocked || isHit -> BorderStroke(1.2.dp, Accent.copy(alpha = 0.75f))
            else -> BorderStroke(1.dp, Surface3.copy(alpha = 0.5f))
        },
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onToggleLock(index) }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            val compactTile = maxHeight < 76.dp
            val badgeSize = if (compactTile) 30.dp else 36.dp
            val noteSp = if (compactTile) 18 else 22
            val centsSp = if (compactTile) 13 else 16

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 上行：弦号圆章 + 音名 + 播放/停止参考音
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = when {
                            isHit -> Accent
                            isLocked -> Accent.copy(alpha = 0.25f)
                            else -> Surface3
                        },
                        modifier = Modifier.size(badgeSize)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    if (isPlaying) onStopReference() else onPlayReference(index)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = number.toString(),
                                fontSize = if (compactTile) 13.sp else 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isHit) Color(0xFF04211A) else TextMain
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = note.name(state.useFlats),
                        fontSize = noteSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isTarget || isLocked || stringInTune) TextMain else TextMuted,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )

                    if (isPlaying) {
                        Surface(
                            onClick = onStopReference,
                            shape = RoundedCornerShape(10.dp),
                            color = Danger.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Danger.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                StopSquareIcon(color = Danger, sizeDp = 9)
                                Text(
                                    text = "停止",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Danger,
                                    maxLines = 1
                                )
                            }
                        }
                    } else {
                        IconButton(
                            onClick = { onPlayReference(index) },
                            modifier = Modifier.size(if (compactTile) 30.dp else 36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "循环播放${note.name(state.useFlats)}参考音",
                                modifier = Modifier.size(if (compactTile) 18.dp else 20.dp),
                                tint = TextMuted
                            )
                        }
                    }
                }

                // 下行：保留的音分差值 + 锁定/音准状态标签
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatCents(displayCents),
                        fontSize = centsSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            displayCents == null -> TextMuted.copy(alpha = 0.55f)
                            stringInTune -> Accent
                            displayCents > 0 -> Warm
                            else -> InfoBlue
                        },
                        maxLines = 1
                    )

                    val tagText = when {
                        isHit -> "准"
                        isLocked -> "已锁定"
                        stringInTune -> "准"
                        else -> null
                    }
                    if (tagText != null) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Accent.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = tagText,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Accent,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 大屏 / 悬停设置与快捷调弦卡片

@Composable
fun TunerQuickParamsCard(
    state: TunerUiState,
    onA4Click: () -> Unit,
    onFlatsToggle: () -> Unit,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Surface1,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.tuning.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.tuning.joinedNames(state.useFlats),
                        fontSize = 12.sp,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TunerChip(
                    text = "A4 ${formatOneDecimal(state.a4)} Hz",
                    onClick = onA4Click
                )
                TunerChip(
                    text = if (state.useFlats) "♭ 降号" else "♯ 升号",
                    onClick = onFlatsToggle
                )
                TunerChip(
                    text = "调弦管理 / 排序",
                    onClick = onManageClick
                )
            }
        }
    }
}

@Composable
fun QuickTuningSwitcherCard(
    tunings: List<Tuning>,
    activeId: String,
    useFlats: Boolean,
    onSelect: (Tuning) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Surface1,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "快速切换调弦",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain
                )
                TextButton(
                    onClick = onManage,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("管理 / 排序", fontSize = 12.sp, color = Accent)
                }
            }

            tunings.forEach { tuning ->
                val active = tuning.id == activeId
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (active) Accent.copy(alpha = 0.14f) else Surface2.copy(alpha = 0.55f),
                    border = if (active) BorderStroke(1.dp, Accent.copy(alpha = 0.55f)) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(tuning) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = tuning.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (active) Accent else TextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (!tuning.builtIn) {
                                    Text(
                                        text = "自定义",
                                        fontSize = 10.sp,
                                        color = InfoBlue,
                                        maxLines = 1
                                    )
                                }
                            }
                            Text(
                                text = tuning.joinedNames(useFlats),
                                fontSize = 11.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (active) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "当前调弦",
                                tint = Accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StopSquareIcon(color: Color, sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}

// ------------------------------------------------------------------ 权限 / 错误

@Composable
fun PermissionRequestCard(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Surface1,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "需要麦克风权限",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMain
            )
            Text(
                text = "调音器需要读取麦克风来识别琴弦音高。声音只在本机实时分析，不录制、不上传。",
                fontSize = 13.sp,
                color = TextMuted,
                lineHeight = 19.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onRequest) { Text("去授权") }
                TextButton(onClick = onOpenSettings) { Text("系统设置") }
            }
        }
    }
}

@Composable
fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Danger.copy(alpha = 0.15f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = Danger,
                lineHeight = 18.sp
            )
            TextButton(onClick = onDismiss) {
                Text("知道了", fontSize = 13.sp)
            }
        }
    }
}
