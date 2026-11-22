package com.gtool.feature.tuner

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
            fontWeight = FontWeight.Medium
        )
    }
}

// ------------------------------------------------------------------ 半圆表盘

/**
 * -50..+50 音分的半圆表盘，指针位置平滑动画；±3 音分安全区始终标绿。
 */
@Composable
fun CentsGauge(
    cents: Float?,
    enabled: Boolean,
    modifier: Modifier = Modifier
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

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
        ) {
            val strokeWidth = 14.dp.toPx()
            val centerX = size.width / 2f
            val centerY = size.height * 0.9f
            val radius = min(size.width * 0.44f, size.height * 0.82f)
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
            for (value in -50..50 step 5) {
                val angle = Math.toRadians((180f + (value + 50) / 100f * 180f).toDouble())
                val cosAngle = cos(angle).toFloat()
                val sinAngle = sin(angle).toFloat()
                val major = value % 10 == 0
                val outer = radius - strokeWidth / 2f - 5.dp.toPx()
                val inner = outer - (if (major) 10.dp.toPx() else 5.dp.toPx())
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
            drawLine(
                color = needleColor,
                start = Offset(centerX + cosNeedle * startRadius, centerY + sinNeedle * startRadius),
                end = Offset(centerX + cosNeedle * endRadius, centerY + sinNeedle * endRadius),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )

            // 转轴
            drawCircle(color = Surface2, radius = 9.dp.toPx(), center = Offset(centerX, centerY))
            drawCircle(
                color = needleColor,
                radius = 9.dp.toPx(),
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.5.dp.toPx())
            )
        }

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

// ------------------------------------------------------------------ 中央读数

@Composable
fun PitchPanel(
    state: TunerUiState,
    hasPermission: Boolean,
    onPlayTarget: (Int) -> Unit,
    onStopReference: () -> Unit,
    modifier: Modifier = Modifier
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
        note == null -> "拨动琴弦开始调音"
        else -> "当前音高"
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Surface1,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = hint, fontSize = 13.sp, color = TextMuted)

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
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = centsColor
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = tuningStatusText(state),
                    fontSize = 16.sp,
                    fontWeight = if (state.inTune) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (state.inTune) Accent else TextMuted
                )
            }

            val targetIndex = state.targetIndex
            val target = targetIndex?.let { state.tuning.strings.getOrNull(it) }
            Spacer(Modifier.height(4.dp))
            // 固定单行高度的目标行：避免换行与下方弦位列表上下跳动
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
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
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
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
    }
}

// ------------------------------------------------------------------ 弦位列表

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
