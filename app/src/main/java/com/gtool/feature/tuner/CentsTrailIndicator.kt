package com.gtool.feature.tuner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Danger
import com.gtool.ui.theme.InfoBlue
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface3
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm
import kotlinx.coroutines.flow.first
import kotlin.math.abs

internal fun zoneColor(zone: CentsZone): Color = when (zone) {
    CentsZone.IN_TUNE -> Accent
    CentsZone.SHARP -> Warm
    CentsZone.FLAT -> InfoBlue
}

/**
 * 横轴音分指示器：上方为当前音高 / 频率 / 音分读数，中间为 -50..+50 横轴与左右摆动的指针，
 * 指针向下走出随时间渐隐的轨迹（越直越稳）。填满分配到的区域，四态布局共用。
 *
 * 轨迹每帧采样，只在 Canvas 绘制阶段读取帧时间，不触发每帧重组；无信号且轨迹淡完后停止请求帧。
 */
@Composable
fun CentsTrailIndicator(
    state: TunerUiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cents = state.cents?.toFloat()
    val range = TrailMath.RANGE_CENTS

    // 指针：有信号时平滑跟随，从无信号恢复时直接跳到位，避免轨迹出现假滑行
    val needle = remember { Animatable(0f) }
    val hadSignal = remember { BooleanArray(1) }
    LaunchedEffect(cents) {
        if (cents == null) {
            hadSignal[0] = false
            return@LaunchedEffect
        }
        val target = cents.coerceIn(-range, range)
        if (!hadSignal[0]) {
            hadSignal[0] = true
            needle.snapTo(target)
        } else {
            needle.animateTo(target, tween(durationMillis = 90, easing = LinearOutSlowInEasing))
        }
    }

    val liveCents = rememberUpdatedState(cents)
    val trail = remember { CentsTrail() }
    var frameNow by remember { mutableLongStateOf(0L) }
    LaunchedEffect(trail) {
        var drewLast = false
        while (true) {
            if (trail.size == 0 && liveCents.value == null) {
                // 空闲：不请求帧，等待下一次拾到音
                snapshotFlow { liveCents.value }.first { it != null }
            }
            withFrameMillis { now ->
                trail.push(now, if (liveCents.value != null) needle.value else null)
                trail.prune(now, TrailMath.WINDOW_MS)
                if (trail.size > 0 || drewLast) frameNow = now
                drewLast = trail.size > 0
            }
        }
    }

    val note = state.detectedNote
    val noteColor = when {
        note == null -> TextMuted.copy(alpha = 0.6f)
        state.inTune -> Accent
        else -> TextMain
    }
    val centsColor = cents?.let { zoneColor(TrailMath.zoneOf(it)) } ?: TextMuted.copy(alpha = 0.6f)
    val octaveOff = when {
        cents == null -> null
        cents >= 600f -> "高过八度"
        cents <= -600f -> "低过八度"
        else -> null
    }

    val textMeasurer = rememberTextMeasurer()
    val tickLabels = remember(textMeasurer) {
        listOf(-50f to "-50", -25f to "-25", 0f to "0", 25f to "+25", 50f to "+50").map { (value, text) ->
            value to textMeasurer.measure(
                text,
                TextStyle(
                    fontSize = 11.sp,
                    fontWeight = if (value == 0f) FontWeight.SemiBold else FontWeight.Normal,
                    color = TextMuted.copy(alpha = if (value == 0f) 0.9f else 0.7f)
                )
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Surface1,
        modifier = modifier
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val noteSp = (maxHeight.value * 0.16f).coerceIn(38f, 88f)
            val infoSp = (noteSp * 0.24f).coerceIn(13f, 20f)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 6.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 当前音高
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = note?.pitchClass?.name(state.useFlats) ?: "—",
                        fontSize = noteSp.sp,
                        lineHeight = (noteSp * 1.04f).sp,
                        fontWeight = FontWeight.Bold,
                        color = noteColor,
                        maxLines = 1
                    )
                    if (note != null) {
                        Text(
                            text = note.octave.toString(),
                            fontSize = (noteSp * 0.4f).sp,
                            fontWeight = FontWeight.SemiBold,
                            color = noteColor.copy(alpha = 0.75f),
                            modifier = Modifier.padding(start = 2.dp, bottom = (noteSp * 0.13f).dp),
                            maxLines = 1
                        )
                    }
                }

                // 频率 + 音分差值
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = state.frequency?.let { "${formatOneDecimal(it)} Hz" } ?: "— Hz",
                        fontSize = infoSp.sp,
                        color = TextMuted,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(18.dp))
                    Text(
                        text = formatCents(state.cents),
                        fontSize = (infoSp * 1.15f).sp,
                        fontWeight = FontWeight.Bold,
                        color = centsColor,
                        maxLines = 1
                    )
                    if (octaveOff != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = octaveOff,
                            fontSize = (infoSp * 0.9f).sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Danger,
                            maxLines = 1
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val inTune = state.inTune
                    val signal = cents != null
                    Canvas(Modifier.fillMaxSize()) {
                        val now = frameNow
                        val pad = 22.dp.toPx()
                        val left = pad
                        val right = size.width - pad
                        val axisWidth = (right - left).coerceAtLeast(1f)
                        val labelHeight = tickLabels.maxOf { it.second.size.height }.toFloat()
                        val axisY = labelHeight + 22.dp.toPx()
                        val trailTop = axisY
                        val trailHeight = (size.height - trailTop).coerceAtLeast(1f)
                        fun xOf(value: Float) = left + TrailMath.centsToFraction(value) * axisWidth

                        // ±3 音分绿色竖带，贯穿轨迹区
                        val bandTop = axisY - 14.dp.toPx()
                        drawRect(
                            color = Accent.copy(alpha = if (inTune) 0.18f else 0.08f),
                            topLeft = Offset(xOf(-3f), bandTop),
                            size = Size(xOf(3f) - xOf(-3f), size.height - bandTop)
                        )
                        // 中心与 ±25 参考线
                        for (guide in listOf(-25f, 0f, 25f)) {
                            drawLine(
                                color = TextMuted.copy(alpha = if (guide == 0f) 0.24f else 0.09f),
                                start = Offset(xOf(guide), axisY),
                                end = Offset(xOf(guide), size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        // 横轴与刻度
                        drawLine(
                            color = Surface3,
                            start = Offset(left, axisY),
                            end = Offset(right, axisY),
                            strokeWidth = 3.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = Accent.copy(alpha = if (inTune) 0.95f else 0.45f),
                            start = Offset(xOf(-3f), axisY),
                            end = Offset(xOf(3f), axisY),
                            strokeWidth = 3.5.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        for (value in -50..50 step 5) {
                            val length = when {
                                value == 0 -> 14.dp
                                value % 25 == 0 -> 11.dp
                                value % 10 == 0 -> 8.dp
                                else -> 5.dp
                            }.toPx()
                            val x = xOf(value.toFloat())
                            drawLine(
                                color = TextMuted.copy(alpha = if (value % 10 == 0 || value % 25 == 0) 0.8f else 0.4f),
                                start = Offset(x, axisY - length),
                                end = Offset(x, axisY),
                                strokeWidth = (if (value % 10 == 0) 2.dp else 1.dp).toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                        tickLabels.forEach { (value, layout) ->
                            val x = (xOf(value) - layout.size.width / 2f)
                                .coerceIn(0f, size.width - layout.size.width)
                            drawText(layout, topLeft = Offset(x, 0f))
                        }

                        // 指针与向下渐隐轨迹
                        val needleValue = needle.value
                        val needleColor = when {
                            !signal -> TextMuted.copy(alpha = 0.32f)
                            else -> zoneColor(TrailMath.zoneOf(cents ?: 0f))
                        }
                        val nx = if (signal) xOf(needleValue) else xOf(0f)

                        // 轨迹：从横轴往下走出，越老越靠下、越淡、越细
                        val thick = 4.dp.toPx()
                        val thin = 1.4.dp.toPx()
                        if (signal && trail.size > 0) {
                            val lastIdx = trail.size - 1
                            val lastCents = trail.centsAt(lastIdx)
                            val lastTime = trail.timeAt(lastIdx)
                            if (!lastCents.isNaN() && now - lastTime in 1..TrailMath.MAX_SEGMENT_GAP_MS) {
                                val yLast = trailTop + TrailMath.ageFraction(now - lastTime) * trailHeight
                                drawLine(
                                    color = needleColor.copy(alpha = 0.9f),
                                    start = Offset(nx, trailTop),
                                    end = Offset(xOf(lastCents), yLast),
                                    strokeWidth = thick,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                        for (i in 1 until trail.size) {
                            val c0 = trail.centsAt(i - 1)
                            val c1 = trail.centsAt(i)
                            if (c0.isNaN() || c1.isNaN()) continue
                            val t0 = trail.timeAt(i - 1)
                            val t1 = trail.timeAt(i)
                            if (t1 - t0 > TrailMath.MAX_SEGMENT_GAP_MS) continue
                            val alpha = TrailMath.ageAlpha(now - t1)
                            if (alpha <= 0f) continue
                            val y0 = trailTop + TrailMath.ageFraction(now - t0) * trailHeight
                            val y1 = trailTop + TrailMath.ageFraction(now - t1) * trailHeight
                            drawLine(
                                color = zoneColor(TrailMath.zoneOf(c1)).copy(alpha = 0.9f * alpha),
                                start = Offset(xOf(c0), y0),
                                end = Offset(xOf(c1), y1),
                                strokeWidth = thin + (thick - thin) * alpha,
                                cap = StrokeCap.Round
                            )
                        }

                        // 指针本体
                        val headY = axisY - 18.dp.toPx()
                        if (inTune) {
                            drawCircle(
                                color = Accent.copy(alpha = 0.25f),
                                radius = 11.dp.toPx(),
                                center = Offset(nx, headY)
                            )
                        }
                        drawLine(
                            color = needleColor,
                            start = Offset(nx, headY),
                            end = Offset(nx, axisY + 6.dp.toPx()),
                            strokeWidth = 4.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        drawCircle(color = needleColor, radius = 6.dp.toPx(), center = Offset(nx, headY))
                        if (signal && abs(cents ?: 0f) > range) {
                            // 超量程：指针端点外侧画小箭头提示方向
                            val dir = if ((cents ?: 0f) > 0f) 1f else -1f
                            drawLine(
                                color = needleColor,
                                start = Offset(nx - dir * 10.dp.toPx(), headY - 6.dp.toPx()),
                                end = Offset(nx, headY),
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = needleColor,
                                start = Offset(nx - dir * 10.dp.toPx(), headY + 6.dp.toPx()),
                                end = Offset(nx, headY),
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    if (!hasPermission) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(
                                onClick = onRequestPermission,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                            ) {
                                Text("授予麦克风权限", fontSize = 14.sp)
                            }
                            TextButton(onClick = onOpenSettings) {
                                Text("系统设置", fontSize = 13.sp, color = TextMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}
