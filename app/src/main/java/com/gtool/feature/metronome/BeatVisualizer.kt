package com.gtool.feature.metronome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.data.MetronomeSettings
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Bg
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm
import kotlin.math.max
import kotlin.math.min

private val StageShape = RoundedCornerShape(22.dp)
private val HintPillShape = RoundedCornerShape(999.dp)

// 重拍亮态暖金渐变色阶
private val WarmLitStart = Color(0xFFFF9F43)
private val WarmLitMid = Color(0xFFFFD796)
private val WarmLitEnd = Color(0xFFFFE9C2)

// 普通拍亮态薄荷绿渐变色阶
private val AccentLitStart = Color(0xFF1CD698)
private val AccentLitEnd = Color(0xFF6EFAC9)
private val AccentLitRim = Color(0xFF8CFFDA)

/**
 * 竖屏主视觉：左对齐横条竖向堆叠 + 拍号标尺（方案 B）。
 *
 * 整块舞台同时承担开始 / 暂停触控区；脉冲包络仅在 Canvas / drawBehind 阶段读取，
 * 避免每帧触发 Compose 重组。
 */
@Composable
internal fun HorizontalStackedBeatStage(
    settings: MetronomeSettings,
    pulse: BeatPulseState,
    playing: Boolean,
    showTapHint: Boolean,
    onTogglePlay: () -> Unit,
    onDismissTapHint: () -> Unit,
    modifier: Modifier = Modifier
) {
    val beats = settings.beatsPerBar.coerceAtLeast(1)
    val subdivision = settings.subdivision.coerceAtLeast(1)
    val rowSpacing = when {
        beats <= 4 -> 10.dp
        beats <= 6 -> 7.dp
        else -> 4.dp
    }
    val verticalPadding = if (beats > 6) 14.dp else 18.dp
    val compactLabels = beats > 8
    val density = LocalDensity.current
    val dashEffect = remember(density) {
        with(density) {
            PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
        }
    }

    BeatStageContainer(
        pulse = pulse,
        playing = playing,
        showTapHint = showTapHint,
        onTogglePlay = onTogglePlay,
        onDismissTapHint = onDismissTapHint,
        modifier = modifier
    ) {
        val currentBeat = if (playing && pulse.activeBeat >= 0) {
            Math.floorMod(pulse.activeBeat, beats)
        } else {
            -1
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(rowSpacing, Alignment.CenterVertically)
        ) {
            for (index in 0 until beats) {
                val level = accentLevelFor(settings.accentPattern, index)
                val isCurrent = currentBeat == index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 14.dp, max = 56.dp)
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "${index + 1}",
                        modifier = Modifier.width(22.dp),
                        color = if (isCurrent) TextMain else TextMuted,
                        style = TextStyle(
                            fontSize = if (compactLabels) 11.sp else 13.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                            textAlign = TextAlign.End
                        ),
                        maxLines = 1
                    )
                    HorizontalBeatBar(
                        beatIndex = index,
                        beatsPerBar = beats,
                        level = level,
                        subdivision = subdivision,
                        playing = playing,
                        pulse = pulse,
                        dashEffect = dashEffect,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

/**
 * 横屏 / 帐篷模式主视觉：底部对齐竖条从左到右排列。
 *
 * 高度比例与暖金 / 薄荷 / 虚线空心三档视觉语言与竖屏完全一致，
 * 脉冲包络同样仅在 draw 阶段读取。
 */
@Composable
internal fun VerticalRowBeatStage(
    settings: MetronomeSettings,
    pulse: BeatPulseState,
    playing: Boolean,
    showTapHint: Boolean,
    onTogglePlay: () -> Unit,
    onDismissTapHint: () -> Unit,
    modifier: Modifier = Modifier
) {
    val beats = settings.beatsPerBar.coerceAtLeast(1)
    val subdivision = settings.subdivision.coerceAtLeast(1)
    val columnSpacing = when {
        beats <= 4 -> 12.dp
        beats <= 6 -> 8.dp
        else -> 5.dp
    }
    val compactLabels = beats > 8
    val density = LocalDensity.current
    val dashEffect = remember(density) {
        with(density) {
            PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
        }
    }

    BeatStageContainer(
        pulse = pulse,
        playing = playing,
        showTapHint = showTapHint,
        onTogglePlay = onTogglePlay,
        onDismissTapHint = onDismissTapHint,
        modifier = modifier
    ) {
        val currentBeat = if (playing && pulse.activeBeat >= 0) {
            Math.floorMod(pulse.activeBeat, beats)
        } else {
            -1
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(columnSpacing, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (index in 0 until beats) {
                val level = accentLevelFor(settings.accentPattern, index)
                val isCurrent = currentBeat == index
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f, fill = false)
                        .widthIn(min = 24.dp, max = 76.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VerticalBeatBar(
                        beatIndex = index,
                        beatsPerBar = beats,
                        level = level,
                        subdivision = subdivision,
                        playing = playing,
                        pulse = pulse,
                        dashEffect = dashEffect,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    Text(
                        text = "${index + 1}",
                        color = if (isCurrent) TextMain else TextMuted,
                        style = TextStyle(
                            fontSize = if (compactLabels) 11.sp else 13.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                            textAlign = TextAlign.Center
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 节拍舞台外框：承接全区域点击启停、运行态细边框与极柔和环境光，以及一次性引导提示。
 */
@Composable
private fun BeatStageContainer(
    pulse: BeatPulseState,
    playing: Boolean,
    showTapHint: Boolean,
    onTogglePlay: () -> Unit,
    onDismissTapHint: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val borderColor by animateColorAsState(
        targetValue = if (playing) Accent.copy(alpha = 0.42f) else FretWire.copy(alpha = 0.45f),
        animationSpec = tween(durationMillis = 220),
        label = "beatStageBorder"
    )

    Box(
        modifier = modifier
            .clip(StageShape)
            .background(Surface1)
            .drawBehind {
                if (!playing) return@drawBehind
                val glow = pulse.barIntensity.coerceIn(0f, 1f)
                val ambientColor = if (pulse.activeLevel >= 2) Warm else Accent
                val centerAlpha = 0.045f + 0.05f * glow
                val radius = max(size.width, size.height).coerceAtLeast(1f) * 0.78f
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(ambientColor.copy(alpha = centerAlpha), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.5f),
                        radius = radius
                    )
                )
            }
            .border(width = 1.dp, color = borderColor, shape = StageShape)
            .clickable(
                role = Role.Button,
                onClickLabel = if (playing) "暂停节拍器" else "开始节拍器"
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (showTapHint) onDismissTapHint()
                onTogglePlay()
            }
    ) {
        content()

        AnimatedVisibility(
            visible = showTapHint,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(HintPillShape)
                    .background(Surface2.copy(alpha = 0.90f))
                    .border(width = 1.dp, color = FretWire.copy(alpha = 0.65f), shape = HintPillShape)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "轻触节拍区可开始 / 暂停",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 水平左对齐胶囊拍条（竖屏堆叠使用）。
 * 在绘制阶段读取 [BeatPulseState.barIntensity] 与 [BeatPulseState.activeSub]，零重组刷新。
 */
@Composable
private fun HorizontalBeatBar(
    beatIndex: Int,
    beatsPerBar: Int,
    level: Int,
    subdivision: Int,
    playing: Boolean,
    pulse: BeatPulseState,
    dashEffect: PathEffect,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val currentBeat = if (playing && pulse.activeBeat >= 0) {
            Math.floorMod(pulse.activeBeat, beatsPerBar)
        } else {
            -1
        }
        val isCurrent = currentBeat == beatIndex
        val pulseBoost = if (isCurrent) pulse.barIntensity.coerceIn(0f, 1f) else 0f
        val activeFloor = when {
            !isCurrent -> 0f
            level >= 2 -> 0.65f
            level == 1 -> 0.58f
            else -> 0.45f
        }
        val glow = max(pulseBoost, activeFloor)

        val hInset = 4.dp.toPx()
        val trackWidth = (size.width - hInset * 2f).coerceAtLeast(1f)
        val minBarPx = min(size.height, 14.dp.toPx())
        val maxBarPx = min(size.height, 44.dp.toPx())
        val baseHeight = (size.height * 0.80f).coerceIn(minBarPx, max(minBarPx, maxBarPx))

        // 极淡的标尺轨道底槽，让 100% / 68% / 38% 长度差异一眼可读
        val trackTop = (size.height - baseHeight) * 0.5f
        val trackRadius = CornerRadius(baseHeight * 0.5f, baseHeight * 0.5f)
        drawRoundRect(
            color = Surface2.copy(alpha = 0.28f),
            topLeft = Offset(hInset, trackTop),
            size = Size(trackWidth, baseHeight),
            cornerRadius = trackRadius
        )

        val widthFraction = when {
            level >= 2 -> 1.00f
            level == 1 -> 0.68f
            else -> 0.38f
        }
        val heightPop = when {
            level >= 2 -> 0.08f
            level == 1 -> 0.06f
            else -> 0.03f
        }
        val barHeight = (baseHeight * (1f + heightPop * glow)).coerceAtMost(size.height)
        val barWidth = (trackWidth * widthFraction).coerceIn(min(barHeight, trackWidth), trackWidth)
        val left = hInset
        val top = (size.height - barHeight) * 0.5f
        val radiusPx = barHeight * 0.5f
        val cornerRadius = CornerRadius(radiusPx, radiusPx)
        val barSize = Size(barWidth, barHeight)
        val barTopLeft = Offset(left, top)

        drawStyledBeatCapsule(
            level = level,
            isCurrent = isCurrent,
            glow = glow,
            topLeft = barTopLeft,
            barSize = barSize,
            cornerRadius = cornerRadius,
            horizontal = true,
            dashEffect = dashEffect
        )

        if (subdivision > 1 && isCurrent) {
            drawSubdivisionDotsHorizontal(
                subCount = subdivision,
                activeSub = pulse.activeSub.coerceIn(0, subdivision - 1),
                level = level,
                barTopLeft = barTopLeft,
                barSize = barSize
            )
        }
    }
}

/**
 * 垂直底部对齐胶囊拍条（横屏 / 帐篷模式使用）。
 * 在绘制阶段读取 [BeatPulseState.barIntensity] 与 [BeatPulseState.activeSub]，零重组刷新。
 */
@Composable
private fun VerticalBeatBar(
    beatIndex: Int,
    beatsPerBar: Int,
    level: Int,
    subdivision: Int,
    playing: Boolean,
    pulse: BeatPulseState,
    dashEffect: PathEffect,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val currentBeat = if (playing && pulse.activeBeat >= 0) {
            Math.floorMod(pulse.activeBeat, beatsPerBar)
        } else {
            -1
        }
        val isCurrent = currentBeat == beatIndex
        val pulseBoost = if (isCurrent) pulse.barIntensity.coerceIn(0f, 1f) else 0f
        val activeFloor = when {
            !isCurrent -> 0f
            level >= 2 -> 0.65f
            level == 1 -> 0.58f
            else -> 0.45f
        }
        val glow = max(pulseBoost, activeFloor)

        val vInset = 5.dp.toPx()
        val trackHeight = (size.height - vInset * 2f).coerceAtLeast(1f)
        val minWidthPx = min(size.width, 14.dp.toPx())
        val maxWidthPx = min(size.width, 44.dp.toPx())
        val baseWidth = (size.width * 0.72f).coerceIn(minWidthPx, max(minWidthPx, maxWidthPx))

        // 底部对齐标尺底槽，直观呈现 100% / 68% / 36% 高度阶梯
        val trackLeft = (size.width - baseWidth) * 0.5f
        val trackRadius = CornerRadius(baseWidth * 0.5f, baseWidth * 0.5f)
        drawRoundRect(
            color = Surface2.copy(alpha = 0.28f),
            topLeft = Offset(trackLeft, vInset),
            size = Size(baseWidth, trackHeight),
            cornerRadius = trackRadius
        )

        val heightFraction = when {
            level >= 2 -> 1.00f
            level == 1 -> 0.68f
            else -> 0.36f
        }
        val widthPop = when {
            level >= 2 -> 0.08f
            level == 1 -> 0.06f
            else -> 0.03f
        }
        val barWidth = (baseWidth * (1f + widthPop * glow)).coerceAtMost(size.width)
        val barHeight = (trackHeight * heightFraction).coerceIn(min(barWidth, trackHeight), trackHeight)
        val left = (size.width - barWidth) * 0.5f
        val top = size.height - vInset - barHeight
        val radiusPx = barWidth * 0.5f
        val cornerRadius = CornerRadius(radiusPx, radiusPx)
        val barSize = Size(barWidth, barHeight)
        val barTopLeft = Offset(left, top)

        drawStyledBeatCapsule(
            level = level,
            isCurrent = isCurrent,
            glow = glow,
            topLeft = barTopLeft,
            barSize = barSize,
            cornerRadius = cornerRadius,
            horizontal = false,
            dashEffect = dashEffect
        )

        if (subdivision > 1 && isCurrent) {
            drawSubdivisionDotsVertical(
                subCount = subdivision,
                activeSub = pulse.activeSub.coerceIn(0, subdivision - 1),
                level = level,
                barTopLeft = barTopLeft,
                barSize = barSize
            )
        }
    }
}

/**
 * 统一绘制三档重音胶囊（外光晕 + 渐变本体 + 边缘高光 + 边框）。
 */
private fun DrawScope.drawStyledBeatCapsule(
    level: Int,
    isCurrent: Boolean,
    glow: Float,
    topLeft: Offset,
    barSize: Size,
    cornerRadius: CornerRadius,
    horizontal: Boolean,
    dashEffect: PathEffect
) {
    val strokeWidth = 1.15.dp.toPx()

    when {
        level >= 2 -> {
            // 重拍：暖金外光晕 + 三段暖金高光渐变 + 顶部镜面高光线
            if (glow > 0.005f) {
                drawOuterHalo(
                    colorOuter = Warm,
                    colorInner = WarmLitMid,
                    glow = glow,
                    topLeft = topLeft,
                    barSize = barSize,
                    cornerRadius = cornerRadius,
                    maxExpandPx = 6.dp.toPx(),
                    outerAlpha = 0.24f,
                    innerAlpha = 0.30f
                )
            }

            // 静息底色
            drawRoundRect(
                color = Warm.copy(alpha = 0.22f),
                topLeft = topLeft,
                size = barSize,
                cornerRadius = cornerRadius
            )

            // 亮态渐变叠加
            if (glow > 0.005f) {
                val fillBrush = if (horizontal) {
                    Brush.horizontalGradient(
                        colors = listOf(WarmLitStart, WarmLitMid, WarmLitEnd),
                        startX = topLeft.x,
                        endX = (topLeft.x + barSize.width).coerceAtLeast(topLeft.x + 1f)
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(WarmLitEnd, WarmLitMid, WarmLitStart),
                        startY = topLeft.y,
                        endY = (topLeft.y + barSize.height).coerceAtLeast(topLeft.y + 1f)
                    )
                }
                drawRoundRect(
                    brush = fillBrush,
                    topLeft = topLeft,
                    size = barSize,
                    cornerRadius = cornerRadius,
                    alpha = glow
                )
                drawSpecularHighlight(
                    topLeft = topLeft,
                    barSize = barSize,
                    cornerRadius = cornerRadius,
                    horizontal = horizontal,
                    alpha = 0.72f * glow
                )
            }

            val borderColor = if (glow > 0.005f) {
                WarmLitEnd.copy(alpha = 0.50f + 0.45f * glow)
            } else {
                Warm.copy(alpha = 0.50f)
            }
            drawRoundRect(
                color = borderColor,
                topLeft = topLeft,
                size = barSize,
                cornerRadius = cornerRadius,
                style = Stroke(width = strokeWidth)
            )
        }

        level == 1 -> {
            // 普通拍：翡翠薄荷外光晕 + 翠绿渐变 + 边缘高光
            if (glow > 0.005f) {
                drawOuterHalo(
                    colorOuter = Accent,
                    colorInner = AccentLitEnd,
                    glow = glow,
                    topLeft = topLeft,
                    barSize = barSize,
                    cornerRadius = cornerRadius,
                    maxExpandPx = 5.dp.toPx(),
                    outerAlpha = 0.19f,
                    innerAlpha = 0.24f
                )
            }

            drawRoundRect(
                color = Accent.copy(alpha = 0.14f),
                topLeft = topLeft,
                size = barSize,
                cornerRadius = cornerRadius
            )

            if (glow > 0.005f) {
                val fillBrush = if (horizontal) {
                    Brush.horizontalGradient(
                        colors = listOf(AccentLitStart, AccentLitEnd),
                        startX = topLeft.x,
                        endX = (topLeft.x + barSize.width).coerceAtLeast(topLeft.x + 1f)
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(AccentLitEnd, AccentLitStart),
                        startY = topLeft.y,
                        endY = (topLeft.y + barSize.height).coerceAtLeast(topLeft.y + 1f)
                    )
                }
                drawRoundRect(
                    brush = fillBrush,
                    topLeft = topLeft,
                    size = barSize,
                    cornerRadius = cornerRadius,
                    alpha = glow
                )
                drawSpecularHighlight(
                    topLeft = topLeft,
                    barSize = barSize,
                    cornerRadius = cornerRadius,
                    horizontal = horizontal,
                    alpha = 0.55f * glow
                )
            }

            val borderColor = if (glow > 0.005f) {
                AccentLitRim.copy(alpha = 0.34f + 0.54f * glow)
            } else {
                Accent.copy(alpha = 0.34f)
            }
            drawRoundRect(
                color = borderColor,
                topLeft = topLeft,
                size = barSize,
                cornerRadius = cornerRadius,
                style = Stroke(width = strokeWidth)
            )
        }

        else -> {
            // 静音拍：虚线空心胶囊，命中时透出柔和冷白内光
            if (glow > 0.005f) {
                drawRoundRect(
                    color = TextMain.copy(alpha = 0.14f * glow),
                    topLeft = topLeft,
                    size = barSize,
                    cornerRadius = cornerRadius
                )
            }
            val borderColor = if (isCurrent) {
                TextMain.copy(alpha = 0.35f + 0.55f * glow)
            } else {
                TextMuted.copy(alpha = 0.38f)
            }
            drawRoundRect(
                color = borderColor,
                topLeft = topLeft,
                size = barSize,
                cornerRadius = cornerRadius,
                style = Stroke(width = 1.25.dp.toPx(), pathEffect = dashEffect)
            )
        }
    }
}

/**
 * 多层贴合胶囊轮廓的柔和外光晕，避免矩形裁剪硬边并兼顾全机型 GPU 兼容性。
 */
private fun DrawScope.drawOuterHalo(
    colorOuter: Color,
    colorInner: Color,
    glow: Float,
    topLeft: Offset,
    barSize: Size,
    cornerRadius: CornerRadius,
    maxExpandPx: Float,
    outerAlpha: Float,
    innerAlpha: Float
) {
    val mistExpand = maxExpandPx * 1.35f * glow
    drawRoundRect(
        color = colorOuter.copy(alpha = outerAlpha * 0.36f * glow),
        topLeft = Offset(topLeft.x - mistExpand, topLeft.y - mistExpand),
        size = Size(barSize.width + mistExpand * 2f, barSize.height + mistExpand * 2f),
        cornerRadius = CornerRadius(cornerRadius.x + mistExpand, cornerRadius.y + mistExpand)
    )

    val outerExpand = maxExpandPx * 0.78f * glow
    drawRoundRect(
        color = colorOuter.copy(alpha = outerAlpha * 0.72f * glow),
        topLeft = Offset(topLeft.x - outerExpand, topLeft.y - outerExpand),
        size = Size(barSize.width + outerExpand * 2f, barSize.height + outerExpand * 2f),
        cornerRadius = CornerRadius(cornerRadius.x + outerExpand, cornerRadius.y + outerExpand)
    )

    val innerExpand = maxExpandPx * 0.34f * glow
    drawRoundRect(
        color = colorInner.copy(alpha = innerAlpha * glow),
        topLeft = Offset(topLeft.x - innerExpand, topLeft.y - innerExpand),
        size = Size(barSize.width + innerExpand * 2f, barSize.height + innerExpand * 2f),
        cornerRadius = CornerRadius(cornerRadius.x + innerExpand, cornerRadius.y + innerExpand)
    )
}

/**
 * 胶囊边缘镜面高光线：横条沿上缘绘制，竖条沿左缘绘制，增强点亮瞬间质感。
 */
private fun DrawScope.drawSpecularHighlight(
    topLeft: Offset,
    barSize: Size,
    cornerRadius: CornerRadius,
    horizontal: Boolean,
    alpha: Float
) {
    val inset = 1.6.dp.toPx()
    val strokePx = 1.25.dp.toPx()
    if (horizontal) {
        val startX = topLeft.x + cornerRadius.x * 0.72f
        val endX = topLeft.x + barSize.width - cornerRadius.x * 0.72f
        if (endX <= startX + 2f) return
        val y = topLeft.y + inset
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = alpha), Color.Transparent),
                startX = startX,
                endX = endX
            ),
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )
    } else {
        val startY = topLeft.y + cornerRadius.y * 0.72f
        val endY = topLeft.y + barSize.height - cornerRadius.y * 0.72f
        if (endY <= startY + 2f) return
        val x = topLeft.x + inset
        drawLine(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = alpha), Color.Transparent),
                startY = startY,
                endY = endY
            ),
            start = Offset(x, startY),
            end = Offset(x, endY),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )
    }
}

/**
 * 横条内的细分微光点：仅在 subdivision > 1 且当前拍命中时展示。
 */
private fun DrawScope.drawSubdivisionDotsHorizontal(
    subCount: Int,
    activeSub: Int,
    level: Int,
    barTopLeft: Offset,
    barSize: Size
) {
    val baseR = (barSize.height * 0.13f).coerceIn(2.dp.toPx(), 3.2.dp.toPx())
    val maxSpan = (barSize.width - barSize.height).coerceAtLeast(baseR * 2f)
    val preferredStep = baseR * 3.6f
    val step = if (subCount > 1) min(preferredStep, maxSpan / (subCount - 1)) else 0f
    val totalSpan = step * (subCount - 1)
    val startX = barTopLeft.x + (barSize.width - totalSpan) * 0.5f
    val centerY = barTopLeft.y + barSize.height * 0.5f

    for (sub in 0 until subCount) {
        drawSingleSubDot(
            center = Offset(startX + sub * step, centerY),
            baseRadius = baseR,
            isActiveSub = sub == activeSub,
            level = level
        )
    }
}

/**
 * 竖条内的细分微光点：自下而上排列，呼应竖条底部对齐的律动感。
 */
private fun DrawScope.drawSubdivisionDotsVertical(
    subCount: Int,
    activeSub: Int,
    level: Int,
    barTopLeft: Offset,
    barSize: Size
) {
    val baseR = (barSize.width * 0.13f).coerceIn(2.dp.toPx(), 3.2.dp.toPx())
    val maxSpan = (barSize.height - barSize.width).coerceAtLeast(baseR * 2f)
    val preferredStep = baseR * 3.6f
    val step = if (subCount > 1) min(preferredStep, maxSpan / (subCount - 1)) else 0f
    val totalSpan = step * (subCount - 1)
    val centerX = barTopLeft.x + barSize.width * 0.5f
    val startY = barTopLeft.y + (barSize.height + totalSpan) * 0.5f

    for (sub in 0 until subCount) {
        drawSingleSubDot(
            center = Offset(centerX, startY - sub * step),
            baseRadius = baseR,
            isActiveSub = sub == activeSub,
            level = level
        )
    }
}

private fun DrawScope.drawSingleSubDot(
    center: Offset,
    baseRadius: Float,
    isActiveSub: Boolean,
    level: Int
) {
    if (level == 0) {
        if (isActiveSub) {
            drawCircle(
                color = TextMain.copy(alpha = 0.28f),
                radius = baseRadius * 2.1f,
                center = center
            )
            drawCircle(
                color = TextMain.copy(alpha = 0.95f),
                radius = baseRadius * 1.28f,
                center = center
            )
        } else {
            drawCircle(
                color = TextMuted.copy(alpha = 0.45f),
                radius = baseRadius * 0.85f,
                center = center
            )
        }
    } else {
        // 亮态渐变条上用深色暗圈承托高亮点，保证任何亮度下都清晰可辨
        if (isActiveSub) {
            drawCircle(
                color = Bg.copy(alpha = 0.42f),
                radius = baseRadius * 1.9f,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.96f),
                radius = baseRadius * 1.25f,
                center = center
            )
        } else {
            drawCircle(
                color = Bg.copy(alpha = 0.34f),
                radius = baseRadius * 0.85f,
                center = center
            )
        }
    }
}

private fun accentLevelFor(pattern: List<Int>, index: Int): Int {
    if (pattern.isEmpty()) return if (index == 0) 2 else 1
    return pattern[index % pattern.size].coerceIn(0, 2)
}
