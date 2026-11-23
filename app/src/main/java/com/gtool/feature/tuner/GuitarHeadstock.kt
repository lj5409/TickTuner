package com.gtool.feature.tuner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.music.Note
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.Surface3
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import kotlin.math.abs

private val WoodLight = Color(0xFF4F3728)
private val WoodDark = Color(0xFF2B1E16)
private val WoodEdge = Color(0xFF755540)
private val Rosewood = Color(0xFF1C1511)
private val TrussCover = Color(0xFF1A1411)
private val NutBone = Color(0xFFE6DDC8)
private val BronzeString = Color(0xFFD2B48C)
private val SteelString = Color(0xFFC9C6BC)
private val Chrome = Color(0xFFD6D3CA)
private val ChromeDark = Color(0xFF8E8B83)

/**
 * 钢弦民谣吉他 3+3 琴头：弦钮旁标注各弦音高，点击音高锁定 / 解锁该弦；
 * 琴头顶部可点击切换调弦（[showTuningBadge] 为 true 时在琴头上方显示调弦切换徽章，供横屏专注模式使用）。
 */
@Composable
fun GuitarHeadstock(
    state: TunerUiState,
    onToggleLock: (Int) -> Unit,
    onTuningClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTuningBadge: Boolean = false
) {
    val strings = state.tuning.strings
    BoxWithConstraints(modifier) {
        val geo = remember(maxWidth, maxHeight, strings.size) {
            computeHeadstockGeometry(maxWidth.value, maxHeight.value, strings.size)
        }

        val stringZone: (Int) -> CentsZone? = { index ->
            val live = if (state.targetIndex == index) state.cents else null
            (live ?: state.lastStringCents[index])?.let { TrailMath.zoneOf(it.toFloat()) }
        }

        Canvas(Modifier.fillMaxSize()) {
            val k = density
            fun px(v: Float) = v * k
            val cx = px(geo.centerX)
            val nutY = px(geo.nutY)

            // 琴颈 + 品丝 + 品记点
            val neckHalf = px(geo.nutWidth) * 0.5f
            val neckBottom = px(geo.neckBottom)
            drawRect(
                color = Rosewood,
                topLeft = Offset(cx - neckHalf, nutY),
                size = Size(neckHalf * 2f, neckBottom - nutY)
            )
            drawLine(
                color = WoodEdge.copy(alpha = 0.55f),
                start = Offset(cx - neckHalf, nutY),
                end = Offset(cx - neckHalf, neckBottom),
                strokeWidth = 1.5.dp.toPx()
            )
            drawLine(
                color = WoodEdge.copy(alpha = 0.55f),
                start = Offset(cx + neckHalf, nutY),
                end = Offset(cx + neckHalf, neckBottom),
                strokeWidth = 1.5.dp.toPx()
            )
            val fretY = nutY + (neckBottom - nutY) * 0.62f
            drawLine(
                color = FretWire.copy(alpha = 0.9f),
                start = Offset(cx - neckHalf, fretY),
                end = Offset(cx + neckHalf, fretY),
                strokeWidth = 2.dp.toPx()
            )

            // 旋钮杆与旋钮（在琴头下层，先画）
            geo.pegs.forEach { peg ->
                val sign = if (peg.side == PegSide.LEFT) -1f else 1f
                val y = px(peg.y)
                val locked = state.lockedIndex == peg.stringIndex
                val target = state.targetIndex == peg.stringIndex
                drawLine(
                    color = ChromeDark,
                    start = Offset(px(peg.edgeX) - sign * 2.dp.toPx(), y),
                    end = Offset(px(peg.knobX), y),
                    strokeWidth = 4.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                val kw = px(geo.knobWidth)
                val kh = px(geo.knobHeight)
                val knobColor = when {
                    locked -> Accent
                    target && state.cents != null -> zoneColor(TrailMath.zoneOf(state.cents.toFloat()))
                    else -> Chrome
                }
                drawRoundRect(
                    color = knobColor,
                    topLeft = Offset(px(peg.knobX) - kw / 2f, y - kh / 2f),
                    size = Size(kw, kh),
                    cornerRadius = CornerRadius(kw * 0.45f, kh * 0.45f)
                )
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.28f),
                    topLeft = Offset(px(peg.knobX) - kw / 2f, y - kh / 2f),
                    size = Size(kw, kh),
                    cornerRadius = CornerRadius(kw * 0.45f, kh * 0.45f),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            // 琴头外形（顶部为民谣双峰冠）
            val topHalf = px(geo.topWidth) / 2f
            val bodyTop = px(geo.bodyTop)
            val cornerY = px(geo.cornerY)
            val outline = Path().apply {
                moveTo(cx - neckHalf, nutY)
                lineTo(cx - topHalf, cornerY)
                cubicTo(
                    cx - topHalf * 0.9f, bodyTop,
                    cx - topHalf * 0.35f, bodyTop - (cornerY - bodyTop) * 0.3f,
                    cx, bodyTop + (cornerY - bodyTop) * 0.55f
                )
                cubicTo(
                    cx + topHalf * 0.35f, bodyTop - (cornerY - bodyTop) * 0.3f,
                    cx + topHalf * 0.9f, bodyTop,
                    cx + topHalf, cornerY
                )
                lineTo(cx + neckHalf, nutY)
                close()
            }
            drawPath(
                outline,
                brush = Brush.verticalGradient(listOf(WoodLight, WoodDark), startY = bodyTop, endY = nutY)
            )
            drawPath(outline, color = WoodEdge.copy(alpha = 0.8f), style = Stroke(width = 1.8.dp.toPx()))

            // 琴颈调节杆盖板（琴枕上方钟形盖板）
            val coverHeight = (nutY - bodyTop) * 0.14f
            val coverHalfWidth = neckHalf * 0.24f
            val coverBottom = nutY - 7.dp.toPx()
            val coverTop = coverBottom - coverHeight
            val trussPath = Path().apply {
                moveTo(cx, coverTop)
                cubicTo(
                    cx + coverHalfWidth * 1.15f, coverTop + coverHeight * 0.35f,
                    cx + coverHalfWidth, coverBottom - coverHeight * 0.15f,
                    cx + coverHalfWidth * 0.7f, coverBottom
                )
                lineTo(cx - coverHalfWidth * 0.7f, coverBottom)
                cubicTo(
                    cx - coverHalfWidth, coverBottom - coverHeight * 0.15f,
                    cx - coverHalfWidth * 1.15f, coverTop + coverHeight * 0.35f,
                    cx, coverTop
                )
                close()
            }
            drawPath(trussPath, color = TrussCover)
            drawPath(trussPath, color = WoodEdge.copy(alpha = 0.5f), style = Stroke(width = 1.dp.toPx()))

            // 琴弦：琴颈段 + 琴枕到弦轴（低音铜缠弦偏暖、高音裸钢弦偏银）
            geo.pegs.forEach { peg ->
                val i = peg.stringIndex
                val nutX = px(geo.nutStringX.getOrElse(i) { geo.centerX })
                val isTarget = state.targetIndex == i
                val isLocked = state.lockedIndex == i
                val zone = stringZone(i)
                val base = (2.5f - i * 0.25f).coerceAtLeast(1.1f).dp.toPx()
                val idleColor = if (i < 4) BronzeString.copy(alpha = 0.65f) else SteelString.copy(alpha = 0.65f)
                val color = when {
                    isTarget && state.cents != null && zone != null -> zoneColor(zone)
                    isLocked -> Accent
                    else -> idleColor
                }
                val width = if (isTarget || isLocked) base + 1.1.dp.toPx() else base
                drawLine(color, Offset(nutX, nutY), Offset(nutX, neckBottom), width)
                drawLine(color, Offset(nutX, nutY), Offset(px(peg.postX), px(peg.y)), width, cap = StrokeCap.Round)
            }

            // 琴枕
            drawRoundRect(
                color = NutBone,
                topLeft = Offset(cx - neckHalf, nutY - 2.8.dp.toPx()),
                size = Size(neckHalf * 2f, 5.6.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx())
            )

            // 弦轴（缠弦柱 + 金属垫圈）
            val postR = px(geo.postRadius)
            geo.pegs.forEach { peg ->
                val center = Offset(px(peg.postX), px(peg.y))
                val isLocked = state.lockedIndex == peg.stringIndex
                drawCircle(
                    color = if (isLocked) Accent.copy(alpha = 0.85f) else ChromeDark,
                    radius = postR * 1.65f,
                    center = center
                )
                drawCircle(color = Chrome, radius = postR, center = center)
                drawCircle(color = Rosewood, radius = postR * 0.35f, center = center)
            }
        }

        // 琴头顶部点击区（或横屏调弦名胶囊）：点击切换调弦
        val logoWidth = geo.topWidth * 0.92f
        if (showTuningBadge) {
            Surface(
                onClick = onTuningClick,
                shape = RoundedCornerShape(50),
                color = Surface2.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, Surface3),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Text(
                    text = state.tuning.name + " ▾",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .offset(x = (geo.centerX - logoWidth / 2f).dp, y = geo.logoTop.dp)
                    .size(width = logoWidth.dp, height = geo.logoHeight.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onTuningClick)
            )
        }

        // 弦钮旁的音高标签
        geo.pegs.forEach { peg ->
            val index = peg.stringIndex
            val note = strings.getOrNull(index) ?: return@forEach
            val half = geo.labelSize / 2f
            PegLabel(
                note = note,
                useFlats = state.useFlats,
                isTarget = state.targetIndex == index,
                isLocked = state.lockedIndex == index,
                liveCents = if (state.targetIndex == index) state.cents else null,
                lastCents = state.lastStringCents[index],
                sizeDp = geo.labelSize,
                onClick = { onToggleLock(index) },
                modifier = Modifier.offset(x = (peg.labelX - half).dp, y = (peg.y - half).dp)
            )
        }
    }
}

@Composable
private fun PegLabel(
    note: Note,
    useFlats: Boolean,
    isTarget: Boolean,
    isLocked: Boolean,
    liveCents: Double?,
    lastCents: Double?,
    sizeDp: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shownCents = liveCents ?: lastCents
    val zone = shownCents?.let { TrailMath.zoneOf(it.toFloat()) }
    val hit = isTarget && liveCents != null && zone == CentsZone.IN_TUNE
    val background = when {
        isLocked && hit -> Accent
        isLocked -> Accent.copy(alpha = 0.24f)
        isTarget -> Surface3
        else -> Surface2
    }
    val border = when {
        isLocked -> BorderStroke(2.dp, Accent)
        isTarget && zone != null -> BorderStroke(2.dp, zoneColor(zone))
        zone != null -> BorderStroke(1.dp, zoneColor(zone).copy(alpha = 0.55f))
        else -> BorderStroke(1.dp, Surface3)
    }
    val textColor = if (isLocked && hit) Color(0xFF04211A) else TextMain
    val name = note.name(useFlats)

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = background,
        border = border,
        modifier = modifier
            .size(sizeDp.dp)
            .semantics { contentDescription = name + if (isLocked) " 已锁定" else "" }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = name,
                fontSize = (sizeDp * 0.3f).sp,
                lineHeight = (sizeDp * 0.32f).sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1
            )
            if (shownCents != null && abs(shownCents) < 1000) {
                Text(
                    text = formatCentsCompact(shownCents),
                    fontSize = (sizeDp * 0.19f).sp,
                    lineHeight = (sizeDp * 0.21f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isLocked && hit) textColor else zone?.let { zoneColor(it) } ?: TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}
