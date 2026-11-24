package com.gtool.feature.tuner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.graphicsLayer
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

private val WoodLight = Color(0xFF4A3223)
private val WoodMid = Color(0xFF332218)
private val WoodDark = Color(0xFF1F150F)
private val WoodEdge = Color(0xFF8C674E)
private val PurflingLine = Color(0xFFD2B48C)
private val Rosewood = Color(0xFF18120F)
private val TrussCover = Color(0xFF14100E)
private val NutBone = Color(0xFFEDE3CE)
private val PearlInlay = Color(0xFFDCD8CC)
private val BronzeString = Color(0xFFD4AF7A)
private val SteelString = Color(0xFFCCC9C0)
private val Chrome = Color(0xFFE0DDD4)
private val ChromeMid = Color(0xFFB5B1A7)
private val ChromeDark = Color(0xFF7D7A72)

/**
 * 钢弦民谣吉他 3+3 琴头：弦钮旁标注各弦音高，点击音高锁定 / 解锁该弦（自由调弦模式下点击弦位自动退出自由模式并锁定该弦）；
 * 琴头顶部可点击切换调弦（[showTuningBadge] 为 true 时在琴头上方显示调弦/自由调弦切换徽章，供横屏专注模式使用）。
 */
@Composable
fun GuitarHeadstock(
    state: TunerUiState,
    onToggleLock: (Int) -> Unit,
    onTuningClick: () -> Unit,
    onToggleFreeMode: () -> Unit,
    modifier: Modifier = Modifier,
    showTuningBadge: Boolean = false
) {
    val strings = state.tuning.strings
    val topInsetDp = if (showTuningBadge) HEADSTOCK_BADGE_TOP_INSET_DP else 0f
    val headstockAlpha = if (state.freeMode) 0.42f else 1f
    BoxWithConstraints(modifier) {
        val geo = remember(maxWidth, maxHeight, strings.size, topInsetDp) {
            computeHeadstockGeometry(
                width = maxWidth.value,
                height = maxHeight.value,
                stringCount = strings.size,
                topInset = topInsetDp
            )
        }

        val stringZone: (Int) -> CentsZone? = { index ->
            val live = if (state.targetIndex == index) state.cents else null
            (live ?: state.lastStringCents[index])?.let { TrailMath.zoneOf(it.toFloat()) }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = headstockAlpha }
        ) {
            val k = density
            fun px(v: Float) = v * k
            val cx = px(geo.centerX)
            val nutY = px(geo.nutY)
            val shoulderY = px(geo.shoulderY)
            val cornerY = px(geo.cornerY)
            val bodyTop = px(geo.bodyTop)

            // 1. 琴颈指板（自琴枕向下微扩）+ 包边 + 品丝 + 珍珠母贝品记点
            val neckHalf = px(geo.nutWidth) * 0.5f
            val neckBottomHalf = neckHalf * 1.06f
            val neckBottom = px(geo.neckBottom)
            val neckPath = Path().apply {
                moveTo(cx - neckHalf, nutY)
                lineTo(cx + neckHalf, nutY)
                lineTo(cx + neckBottomHalf, neckBottom)
                lineTo(cx - neckBottomHalf, neckBottom)
                close()
            }
            drawPath(
                path = neckPath,
                brush = Brush.horizontalGradient(
                    colors = listOf(Rosewood, Color(0xFF241B16), Rosewood),
                    startX = cx - neckBottomHalf,
                    endX = cx + neckBottomHalf
                )
            )
            // 指板两侧包边
            drawLine(
                color = WoodEdge.copy(alpha = 0.65f),
                start = Offset(cx - neckHalf, nutY),
                end = Offset(cx - neckBottomHalf, neckBottom),
                strokeWidth = 1.6.dp.toPx()
            )
            drawLine(
                color = WoodEdge.copy(alpha = 0.65f),
                start = Offset(cx + neckHalf, nutY),
                end = Offset(cx + neckBottomHalf, neckBottom),
                strokeWidth = 1.6.dp.toPx()
            )
            // 第一品品丝与品记点
            val fretRatio = 0.62f
            val fretY = nutY + (neckBottom - nutY) * fretRatio
            val fretHalf = neckHalf + (neckBottomHalf - neckHalf) * fretRatio
            drawLine(
                color = FretWire.copy(alpha = 0.92f),
                start = Offset(cx - fretHalf + 1.dp.toPx(), fretY),
                end = Offset(cx + fretHalf - 1.dp.toPx(), fretY),
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round
            )
            if (neckBottom - fretY >= 10.dp.toPx()) {
                val dotY = (fretY + neckBottom) * 0.5f
                drawCircle(
                    color = PearlInlay.copy(alpha = 0.35f),
                    radius = 3.2.dp.toPx(),
                    center = Offset(cx, dotY)
                )
            }

            // 2. 旋钮总成（在琴头下层绘制：琴头侧边六角轴套 + 传动轴杆 + 竖向流线型琴钮手柄）
            val kw = px(geo.knobWidth)
            val kh = px(geo.knobHeight)
            val collarW = (kw * 0.42f).coerceAtLeast(3.dp.toPx())
            val collarH = (kh * 0.36f).coerceAtLeast(5.dp.toPx())
            geo.pegs.forEach { peg ->
                val sign = if (peg.side == PegSide.LEFT) -1f else 1f
                val y = px(peg.y)
                val edgeX = px(peg.edgeX)
                val knobX = px(peg.knobX)
                val locked = state.lockedIndex == peg.stringIndex
                val target = state.targetIndex == peg.stringIndex

                // 传动横杆（连接琴头侧边与琴钮手柄）
                drawLine(
                    color = ChromeDark,
                    start = Offset(edgeX - sign * 2.dp.toPx(), y),
                    end = Offset(knobX, y),
                    strokeWidth = 3.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Chrome.copy(alpha = 0.7f),
                    start = Offset(edgeX - sign * 1.dp.toPx(), y - 0.7.dp.toPx()),
                    end = Offset(knobX, y - 0.7.dp.toPx()),
                    strokeWidth = 1.1.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // 琴头边缘金属轴套（Bushing Collar）
                val collarCenterX = edgeX + sign * (collarW * 0.45f)
                drawRoundRect(
                    color = ChromeMid,
                    topLeft = Offset(collarCenterX - collarW / 2f, y - collarH / 2f),
                    size = Size(collarW, collarH),
                    cornerRadius = CornerRadius(1.5.dp.toPx())
                )
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.35f),
                    topLeft = Offset(collarCenterX - collarW / 2f, y - collarH / 2f),
                    size = Size(collarW, collarH),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                    style = Stroke(width = 0.9.dp.toPx())
                )

                // 竖向肾形/梯形琴钮手柄（Kidney / Keystone Tuning Button）
                val knobColor = when {
                    locked -> Accent
                    target && state.cents != null -> zoneColor(TrailMath.zoneOf(state.cents.toFloat()))
                    else -> Chrome
                }
                val buttonPath = buildTuningButtonPath(
                    centerX = knobX,
                    centerY = y,
                    width = kw,
                    height = kh,
                    sign = sign
                )
                drawPath(path = buttonPath, color = knobColor)
                // 手柄内侧高光与立体描边
                drawPath(
                    path = buttonPath,
                    color = Color.White.copy(alpha = if (locked) 0.35f else 0.25f),
                    style = Stroke(width = 1.dp.toPx())
                )
                drawPath(
                    path = buttonPath,
                    color = Color.Black.copy(alpha = 0.38f),
                    style = Stroke(width = 1.1.dp.toPx())
                )
            }

            // 3. 钢弦民谣吉他琴头经典轮廓（琴枕上方 S 形流畅外扩肩部 + 近乎平直的弦钮侧边 + 顶部弧形双肩皇冠顶）
            val topHalf = px(geo.topWidth) * 0.5f
            val shoulderHalf = px(geo.shoulderWidth) * 0.5f
            val outline = buildAcousticHeadstockPath(
                cx = cx,
                bodyTop = bodyTop,
                cornerY = cornerY,
                shoulderY = shoulderY,
                nutY = nutY,
                neckHalf = neckHalf,
                shoulderHalf = shoulderHalf,
                topHalf = topHalf
            )
            drawPath(
                path = outline,
                brush = Brush.verticalGradient(
                    colors = listOf(WoodLight, WoodMid, WoodDark),
                    startY = bodyTop,
                    endY = nutY
                )
            )
            // 琴头中轴微弱高光木纹带
            drawPath(
                path = outline,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        WoodEdge.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    startX = cx - topHalf * 0.7f,
                    endX = cx + topHalf * 0.7f
                )
            )
            // 琴头内层象牙色镶边线（Inner Binding / Purfling）
            val inset = 3.5.dp.toPx()
            if (neckHalf > inset * 2.5f) {
                val innerOutline = buildAcousticHeadstockPath(
                    cx = cx,
                    bodyTop = bodyTop + inset * 1.05f,
                    cornerY = cornerY + inset * 0.7f,
                    shoulderY = shoulderY - inset * 0.4f,
                    nutY = nutY - inset * 0.9f,
                    neckHalf = (neckHalf - inset).coerceAtLeast(4f),
                    shoulderHalf = (shoulderHalf - inset).coerceAtLeast(8f),
                    topHalf = (topHalf - inset).coerceAtLeast(10f)
                )
                drawPath(
                    path = innerOutline,
                    color = PurflingLine.copy(alpha = 0.30f),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            // 琴头外边缘木质包边
            drawPath(
                path = outline,
                color = WoodEdge.copy(alpha = 0.88f),
                style = Stroke(width = 1.8.dp.toPx())
            )

            // 4. 琴头顶部中心珍珠母贝菱形装饰与琴颈调节杆钟形盖板（Truss Rod Bell Cover）
            val bodyHeightPx = nutY - bodyTop
            val crownCrestY = bodyTop + bodyHeightPx * 0.095f
            val diamondHalfW = topHalf * 0.075f
            val diamondHalfH = bodyHeightPx * 0.024f
            val diamondPath = Path().apply {
                moveTo(cx, crownCrestY - diamondHalfH)
                lineTo(cx + diamondHalfW, crownCrestY)
                lineTo(cx, crownCrestY + diamondHalfH)
                lineTo(cx - diamondHalfW, crownCrestY)
                close()
            }
            drawPath(diamondPath, color = PearlInlay.copy(alpha = 0.42f))

            val coverHeight = bodyHeightPx * 0.145f
            val coverHalfWidth = neckHalf * 0.25f
            val coverBottom = nutY - 6.5.dp.toPx()
            val coverTop = coverBottom - coverHeight
            val trussPath = Path().apply {
                moveTo(cx, coverTop)
                cubicTo(
                    cx + coverHalfWidth * 1.18f, coverTop + coverHeight * 0.36f,
                    cx + coverHalfWidth, coverBottom - coverHeight * 0.14f,
                    cx + coverHalfWidth * 0.68f, coverBottom
                )
                lineTo(cx - coverHalfWidth * 0.68f, coverBottom)
                cubicTo(
                    cx - coverHalfWidth, coverBottom - coverHeight * 0.14f,
                    cx - coverHalfWidth * 1.18f, coverTop + coverHeight * 0.36f,
                    cx, coverTop
                )
                close()
            }
            drawPath(trussPath, color = TrussCover)
            drawPath(trussPath, color = PurflingLine.copy(alpha = 0.42f), style = Stroke(width = 1.dp.toPx()))

            // 5. 琴枕（Bone Nut，置于琴弦下方或刻有弦槽）
            val nutHalfH = 3.dp.toPx()
            drawRoundRect(
                color = NutBone,
                topLeft = Offset(cx - neckHalf, nutY - nutHalfH),
                size = Size(neckHalf * 2f, nutHalfH * 2f),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            drawRoundRect(
                color = WoodDark.copy(alpha = 0.45f),
                topLeft = Offset(cx - neckHalf, nutY - nutHalfH),
                size = Size(neckHalf * 2f, nutHalfH * 2f),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(width = 0.9.dp.toPx())
            )

            // 6. 琴弦：指板段 + 琴枕至弦轴内侧切线（低音铜缠弦偏暖、高音裸钢弦偏银）
            val postR = px(geo.postRadius)
            geo.pegs.forEach { peg ->
                val i = peg.stringIndex
                val sign = if (peg.side == PegSide.LEFT) -1f else 1f
                val nutX = px(geo.nutStringX.getOrElse(i) { geo.centerX })
                val isTarget = state.targetIndex == i
                val isLocked = state.lockedIndex == i
                val zone = stringZone(i)
                val base = (2.4f - i * 0.24f).coerceAtLeast(1.1f).dp.toPx()
                val idleColor = if (i < 4) BronzeString.copy(alpha = 0.72f) else SteelString.copy(alpha = 0.72f)
                val color = when {
                    isTarget && state.cents != null && zone != null -> zoneColor(zone)
                    isLocked -> Accent
                    else -> idleColor
                }
                val width = if (isTarget || isLocked) base + 1.1.dp.toPx() else base
                // 琴弦缠绕在弦轴靠近中轴线一侧的切点处
                val postTangentX = px(peg.postX) - sign * (postR * 0.58f)
                drawLine(color, Offset(nutX, nutY), Offset(nutX, neckBottom), width)
                drawLine(color, Offset(nutX, nutY), Offset(postTangentX, px(peg.y)), width, cap = StrokeCap.Round)
            }

            // 7. 弦轴（金属垫圈 + 缠弦线圈 + 银色弦轴柱顶盖 + 穿弦孔）
            geo.pegs.forEach { peg ->
                val i = peg.stringIndex
                val center = Offset(px(peg.postX), px(peg.y))
                val isTarget = state.targetIndex == i
                val isLocked = state.lockedIndex == i
                val zone = stringZone(i)
                val activeColor = when {
                    isTarget && state.cents != null && zone != null -> zoneColor(zone)
                    isLocked -> Accent
                    else -> null
                }
                // 底层金属六角/圆形垫圈（Washer）
                drawCircle(
                    color = activeColor?.copy(alpha = 0.85f) ?: ChromeDark,
                    radius = postR * 1.68f,
                    center = center
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = postR * 1.68f,
                    center = center,
                    style = Stroke(width = 0.9.dp.toPx())
                )
                // 缠绕在弦轴上的琴弦金属圈
                val coilColor = activeColor ?: if (i < 4) BronzeString else SteelString
                drawCircle(
                    color = coilColor.copy(alpha = 0.9f),
                    radius = postR * 1.18f,
                    center = center
                )
                // 弦轴顶盖与中心穿弦孔
                drawCircle(color = Chrome, radius = postR * 0.92f, center = center)
                drawCircle(color = Rosewood, radius = postR * 0.34f, center = center)
            }
        }

        // 琴头顶部点击区（或横屏调弦/自由调弦切换徽章）
        val logoWidth = geo.topWidth * 0.92f
        if (showTuningBadge) {
            Row(
                modifier = Modifier.align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onToggleFreeMode,
                    shape = RoundedCornerShape(50),
                    color = if (state.freeMode) Accent.copy(alpha = 0.22f) else Surface2.copy(alpha = 0.9f),
                    border = BorderStroke(
                        1.dp,
                        if (state.freeMode) Accent.copy(alpha = 0.6f) else Surface3
                    )
                ) {
                    Text(
                        text = if (state.freeMode) "自由调弦" else state.tuning.compactPitchNames(state.useFlats),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.freeMode) Accent else TextMain,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Surface(
                    onClick = onTuningClick,
                    shape = RoundedCornerShape(50),
                    color = Surface2.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, Surface3)
                ) {
                    Text(
                        text = "调弦 ▾",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
                modifier = Modifier
                    .offset(x = (peg.labelX - half).dp, y = (peg.y - half).dp)
                    .graphicsLayer { alpha = headstockAlpha }
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

/**
 * 构建经典 3+3 原声民谣吉他琴头轮廓：
 * - 琴枕上方（[nutY] -> [shoulderY]）通过 S 形凹转凸过渡弧迅速外扩出琴头下肩；
 * - 弦钮工作区（[shoulderY] -> [cornerY]）两侧保持挺拔修长、微幅外扩；
 * - 顶部（[cornerY] -> [bodyTop]）采用圆润双肩与经典原声吉他中心冠峰（Open-Book / Crown Crest）造型。
 */
private fun buildAcousticHeadstockPath(
    cx: Float,
    bodyTop: Float,
    cornerY: Float,
    shoulderY: Float,
    nutY: Float,
    neckHalf: Float,
    shoulderHalf: Float,
    topHalf: Float
): Path {
    val flareSpan = (nutY - shoulderY).coerceAtLeast(1f)
    val sideSpan = (shoulderY - cornerY).coerceAtLeast(1f)
    val crownSpan = (cornerY - bodyTop).coerceAtLeast(1f)
    return Path().apply {
        // 起点：琴枕左端
        moveTo(cx - neckHalf, nutY)
        // 左侧琴颈至琴头下肩 S 形外扩弧
        cubicTo(
            cx - neckHalf, nutY - flareSpan * 0.44f,
            cx - shoulderHalf * 1.01f, shoulderY + flareSpan * 0.38f,
            cx - shoulderHalf, shoulderY
        )
        // 左侧弦钮区侧边（微收腰后平滑延伸至上肩角）
        cubicTo(
            cx - shoulderHalf * 0.985f, shoulderY - sideSpan * 0.34f,
            cx - topHalf * 1.005f, cornerY + sideSpan * 0.32f,
            cx - topHalf, cornerY
        )
        // 左上圆角肩部至左冠峰
        cubicTo(
            cx - topHalf, bodyTop + crownSpan * 0.28f,
            cx - topHalf * 0.85f, bodyTop + crownSpan * 0.02f,
            cx - topHalf * 0.62f, bodyTop + crownSpan * 0.02f
        )
        // 左冠峰经内弧过渡至中心微凸冠顶
        cubicTo(
            cx - topHalf * 0.38f, bodyTop + crownSpan * 0.02f,
            cx - topHalf * 0.22f, bodyTop + crownSpan * 0.36f,
            cx, bodyTop + crownSpan * 0.14f
        )
        // 中心微凸冠顶经内弧过渡至右冠峰
        cubicTo(
            cx + topHalf * 0.22f, bodyTop + crownSpan * 0.36f,
            cx + topHalf * 0.38f, bodyTop + crownSpan * 0.02f,
            cx + topHalf * 0.62f, bodyTop + crownSpan * 0.02f
        )
        // 右冠峰至右上圆角肩部
        cubicTo(
            cx + topHalf * 0.85f, bodyTop + crownSpan * 0.02f,
            cx + topHalf, bodyTop + crownSpan * 0.28f,
            cx + topHalf, cornerY
        )
        // 右侧弦钮区侧边
        cubicTo(
            cx + topHalf * 1.005f, cornerY + sideSpan * 0.32f,
            cx + shoulderHalf * 0.985f, shoulderY - sideSpan * 0.34f,
            cx + shoulderHalf, shoulderY
        )
        // 右侧琴头下肩至琴枕 S 形收拢弧
        cubicTo(
            cx + shoulderHalf * 1.01f, shoulderY + flareSpan * 0.38f,
            cx + neckHalf, nutY - flareSpan * 0.44f,
            cx + neckHalf, nutY
        )
        close()
    }
}

/**
 * 构建吉他弦钮手柄（外侧微宽、内侧微收的流线型 Kidney / Keystone 旋钮轮廓）。
 */
private fun buildTuningButtonPath(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    sign: Float
): Path {
    val hw = width * 0.5f
    val hh = height * 0.5f
    val innerX = centerX - sign * hw * 0.86f
    val outerX = centerX + sign * hw
    val midInnerX = centerX - sign * hw * 0.96f
    return Path().apply {
        moveTo(centerX, centerY - hh)
        // 顶部向外侧圆角过渡
        cubicTo(
            outerX - sign * hw * 0.1f, centerY - hh,
            outerX, centerY - hh * 0.72f,
            outerX, centerY
        )
        // 外侧向底部圆角过渡
        cubicTo(
            outerX, centerY + hh * 0.72f,
            outerX - sign * hw * 0.1f, centerY + hh,
            centerX, centerY + hh
        )
        // 底部向内侧（靠近传动轴一侧）过渡
        cubicTo(
            innerX, centerY + hh * 0.95f,
            midInnerX, centerY + hh * 0.58f,
            midInnerX, centerY
        )
        // 内侧向顶部闭合
        cubicTo(
            midInnerX, centerY - hh * 0.58f,
            innerX, centerY - hh * 0.95f,
            centerX, centerY - hh
        )
        close()
    }
}
