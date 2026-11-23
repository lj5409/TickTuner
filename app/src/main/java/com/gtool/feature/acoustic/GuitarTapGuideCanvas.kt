package com.gtool.feature.acoustic

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.acoustic.TapPosition
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.InfoBlue
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm

/**
 * 自绘钢弦木吉他双视角敲击与拾音引导图示（零文字遮挡）：
 * - 顶栏独立于画布外：仅显示步骤、目标模态与图例（去掉冗余“正面视图”文字）
 * - 左图（平面图）：展示正/背板（含消音布/封音孔）与脉冲橙色敲击靶点 + 蓝色麦克风对准点（无横穿蓝虚线）
 * - 右图（侧视图）：直观展示吉他悬空离地 ≥ 80 cm 高度，以及蓝色麦克风垂直正对活跃面板/背板 25 cm 拾音距离
 */
@Composable
fun GuitarTapGuideCanvas(
    position: TapPosition,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "tap_target_pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse"
    )
    val density = LocalDensity.current
    val isBack = position == TapPosition.BACK
    val isDecouple = position == TapPosition.DECOUPLE

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 顶栏信息条（独立于图示上方，绝对不遮挡吉他图）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${position.stepIndex}/5 · ${position.title}",
                    color = TextMain,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isDecouple) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "封堵音孔",
                        color = InfoBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(InfoBlue.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = position.targetModesLabel,
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("● 敲击", color = Warm, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("● 麦克风", color = InfoBlue, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(6.dp))

        // 主双视图画布：左 = 正/背板平面图，右 = 侧视拾音距离与离地高度图
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val w = size.width
            val h = size.height
            val splitX = w * 0.54f

            // ---------------------- 左半：平面图（无文字遮挡，无蓝虚线） ----------------------
            val cx = splitX * 0.50f
            val bodyTop = h * 0.14f
            val bodyBottom = h * 0.94f
            val bodyH = bodyBottom - bodyTop
            val maxHalfW = minOf(splitX * 0.36f, bodyH * 0.44f)
            val upperHalfW = maxHalfW * 0.78f
            val waistHalfW = maxHalfW * 0.62f

            // 琴颈
            val neckW = maxHalfW * 0.25f
            drawRoundRect(
                color = Color(0xFF342A23),
                topLeft = Offset(cx - neckW / 2f, h * 0.01f),
                size = Size(neckW, bodyTop + bodyH * 0.20f),
                cornerRadius = CornerRadius(4f, 4f)
            )

            // 箱体八字轮廓
            val bodyPath = Path().apply {
                moveTo(cx, bodyTop)
                cubicTo(
                    cx + upperHalfW * 0.85f, bodyTop,
                    cx + upperHalfW * 1.04f, bodyTop + bodyH * 0.15f,
                    cx + waistHalfW, bodyTop + bodyH * 0.38f
                )
                cubicTo(
                    cx + maxHalfW * 1.12f, bodyTop + bodyH * 0.56f,
                    cx + maxHalfW * 1.05f, bodyTop + bodyH * 0.96f,
                    cx, bodyBottom
                )
                cubicTo(
                    cx - maxHalfW * 1.05f, bodyTop + bodyH * 0.96f,
                    cx - maxHalfW * 1.12f, bodyTop + bodyH * 0.56f,
                    cx - waistHalfW, bodyTop + bodyH * 0.38f
                )
                cubicTo(
                    cx - upperHalfW * 1.04f, bodyTop + bodyH * 0.15f,
                    cx - upperHalfW * 0.85f, bodyTop,
                    cx, bodyTop
                )
                close()
            }

            val woodBrush = if (isBack) {
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF533325), Color(0xFF3A2218)),
                    startY = bodyTop,
                    endY = bodyBottom
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF926D45), Color(0xFF6E4E30)),
                    startY = bodyTop,
                    endY = bodyBottom
                )
            }
            drawPath(bodyPath, brush = woodBrush)
            drawPath(bodyPath, color = Color(0xFFD9BE9A).copy(alpha = 0.65f), style = Stroke(width = 2.6f))

            val soundHoleCenter = Offset(cx, bodyTop + bodyH * 0.33f)
            val soundHoleR = maxHalfW * 0.25f
            val bridgeY = bodyTop + bodyH * 0.63f
            val bridgeW = maxHalfW * 0.86f
            val bridgeH = bodyH * 0.068f

            if (isBack) {
                // 背板中缝线与背板音梁虚线
                drawLine(
                    color = Color(0xFFD6B995).copy(alpha = 0.55f),
                    start = Offset(cx, bodyTop + 4f),
                    end = Offset(cx, bodyBottom - 4f),
                    strokeWidth = 2.6f
                )
                val dash = PathEffect.dashPathEffect(floatArrayOf(9f, 7f))
                for (frac in listOf(0.28f, 0.52f, 0.76f)) {
                    val ly = bodyTop + bodyH * frac
                    val halfSpan = maxHalfW * (if (frac < 0.4f) 0.64f else 0.76f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.14f),
                        start = Offset(cx - halfSpan, ly),
                        end = Offset(cx + halfSpan, ly),
                        strokeWidth = 1.8f,
                        pathEffect = dash
                    )
                }
            } else {
                // 音孔与音孔饰环
                drawCircle(color = Color(0xFF1A1410), radius = soundHoleR, center = soundHoleCenter)
                drawCircle(
                    color = Color(0xFFE6CFAF).copy(alpha = 0.75f),
                    radius = soundHoleR * 1.13f,
                    center = soundHoleCenter,
                    style = Stroke(width = 2.2f)
                )
                if (isDecouple) {
                    drawCircle(color = InfoBlue.copy(alpha = 0.36f), radius = soundHoleR * 1.24f, center = soundHoleCenter)
                    drawCircle(
                        color = InfoBlue,
                        radius = soundHoleR * 1.24f,
                        center = soundHoleCenter,
                        style = Stroke(width = 2.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f)))
                    )
                    val cr = soundHoleR * 0.60f
                    drawLine(
                        color = InfoBlue,
                        start = Offset(soundHoleCenter.x - cr, soundHoleCenter.y - cr),
                        end = Offset(soundHoleCenter.x + cr, soundHoleCenter.y + cr),
                        strokeWidth = 2.6f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = InfoBlue,
                        start = Offset(soundHoleCenter.x + cr, soundHoleCenter.y - cr),
                        end = Offset(soundHoleCenter.x - cr, soundHoleCenter.y + cr),
                        strokeWidth = 2.6f,
                        cap = StrokeCap.Round
                    )
                }

                // 琴码与下弦枕
                drawRoundRect(
                    color = Color(0xFF2B201A),
                    topLeft = Offset(cx - bridgeW / 2f, bridgeY - bridgeH / 2f),
                    size = Size(bridgeW, bridgeH),
                    cornerRadius = CornerRadius(5f, 5f)
                )
                drawLine(
                    color = Color(0xFFF3EFEA),
                    start = Offset(cx - bridgeW * 0.36f, bridgeY - bridgeH * 0.14f),
                    end = Offset(cx + bridgeW * 0.36f, bridgeY - bridgeH * 0.08f),
                    strokeWidth = 2.6f,
                    cap = StrokeCap.Round
                )

                // 6 弦 + 消音布
                for (s in 0 until 6) {
                    val t = (s - 2.5f) / 2.5f
                    val topX = cx + t * neckW * 0.36f
                    val botX = cx + t * bridgeW * 0.30f
                    drawLine(
                        color = Color(0xFFDCE6E1).copy(alpha = 0.45f),
                        start = Offset(topX, h * 0.02f),
                        end = Offset(botX, bridgeY + bridgeH * 0.16f),
                        strokeWidth = 1.3f
                    )
                }
                val muteY = bodyTop + bodyH * 0.12f
                drawRoundRect(
                    color = Accent.copy(alpha = 0.30f),
                    topLeft = Offset(cx - neckW * 0.65f, muteY - 6f),
                    size = Size(neckW * 1.3f, 12f),
                    cornerRadius = CornerRadius(5f, 5f)
                )
                drawRoundRect(
                    color = Accent.copy(alpha = 0.78f),
                    topLeft = Offset(cx - neckW * 0.65f, muteY - 6f),
                    size = Size(neckW * 1.3f, 12f),
                    cornerRadius = CornerRadius(5f, 5f),
                    style = Stroke(width = 1.5f)
                )
            }

            // 平面图敲击点（橙色脉冲）与麦克风垂直对准点（蓝色实心点，无蓝虚线）
            val tapPt = when (position) {
                TapPosition.BRIDGE, TapPosition.DECOUPLE -> Offset(cx - bridgeW * 0.14f, bridgeY + bridgeH * 0.10f)
                TapPosition.DIPOLE_L -> Offset(cx - bridgeW * 0.44f, bridgeY)
                TapPosition.DIPOLE_V -> Offset(cx, bridgeY + bodyH * 0.15f)
                TapPosition.BACK -> Offset(cx, bodyTop + bodyH * 0.66f)
            }
            val micProjPt = when (position) {
                TapPosition.BRIDGE, TapPosition.DECOUPLE -> Offset(cx + bridgeW * 0.12f, bridgeY - bridgeH * 0.95f)
                TapPosition.DIPOLE_L -> Offset(cx - bridgeW * 0.28f, bridgeY - bridgeH * 1.05f)
                TapPosition.DIPOLE_V -> Offset(cx + maxHalfW * 0.16f, bridgeY + bodyH * 0.10f)
                TapPosition.BACK -> Offset(cx + maxHalfW * 0.16f, bodyTop + bodyH * 0.60f)
            }

            // 橙色敲击靶点
            drawCircle(
                color = Warm.copy(alpha = (1f - pulse) * 0.75f),
                radius = 10f + pulse * 22f,
                center = tapPt,
                style = Stroke(width = 3f)
            )
            drawCircle(color = Warm.copy(alpha = 0.25f), radius = 14f, center = tapPt)
            drawCircle(color = Warm, radius = 6.5f, center = tapPt)
            drawCircle(color = Color.White, radius = 2.6f, center = tapPt)

            // 蓝色麦克风俯视对准点（无虚线）
            drawCircle(color = InfoBlue.copy(alpha = 0.24f), radius = 13f, center = micProjPt)
            drawCircle(color = InfoBlue, radius = 5.5f, center = micProjPt)
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 2f,
                center = micProjPt
            )

            // ---------------------- 分隔竖线 ----------------------
            drawLine(
                color = FretWire.copy(alpha = 0.45f),
                start = Offset(splitX, h * 0.06f),
                end = Offset(splitX, h * 0.94f),
                strokeWidth = 1.2f
            )

            // ---------------------- 右半：侧视拾音高度与距离示意图 ----------------------
            val rightW = w - splitX
            val groundY = h * 0.87f
            val sideBodyTop = h * 0.24f
            val sideBodyBottom = h * 0.66f
            val sideBodyH = sideBodyBottom - sideBodyTop
            val sideBodyW = (rightW * 0.16f).coerceIn(18f, 34f)
            val guitarLeftX = splitX + rightW * 0.22f
            val guitarRightX = guitarLeftX + sideBodyW

            // 地面基准线与斜纹
            drawLine(
                color = FretWire,
                start = Offset(splitX + rightW * 0.08f, groundY),
                end = Offset(w - rightW * 0.06f, groundY),
                strokeWidth = 2f
            )
            var gx = splitX + rightW * 0.12f
            while (gx < w - rightW * 0.10f) {
                drawLine(
                    color = FretWire.copy(alpha = 0.55f),
                    start = Offset(gx, groundY),
                    end = Offset(gx - 6f, groundY + 6f),
                    strokeWidth = 1.3f
                )
                gx += 14f
            }

            // 侧视吉他琴颈与琴头（悬空向上）
            val neckX = if (isBack) guitarLeftX else guitarRightX - sideBodyW * 0.24f
            drawRoundRect(
                color = Color(0xFF3E3027),
                topLeft = Offset(neckX, h * 0.04f),
                size = Size(sideBodyW * 0.24f, sideBodyTop - h * 0.03f),
                cornerRadius = CornerRadius(3f, 3f)
            )
            // 侧视共鸣箱体（右侧永远是正对麦克风的那一面：面板或背板）
            drawRoundRect(
                color = Color(0xFF5A3B28),
                topLeft = Offset(guitarLeftX, sideBodyTop),
                size = Size(sideBodyW, sideBodyH),
                cornerRadius = CornerRadius(6f, 6f)
            )
            drawRoundRect(
                color = Color(0xFFD5B994).copy(alpha = 0.6f),
                topLeft = Offset(guitarLeftX, sideBodyTop),
                size = Size(sideBodyW, sideBodyH),
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 1.8f)
            )
            // 高亮朝向麦克风的有效振面（正面浅云杉 / 背面深玫瑰木）
            drawLine(
                color = if (isBack) Color(0xFFA87155) else Color(0xFFE5C494),
                start = Offset(guitarRightX, sideBodyTop + 4f),
                end = Offset(guitarRightX, sideBodyBottom - 4f),
                strokeWidth = 4f,
                cap = StrokeCap.Round
            )

            // 侧视敲击点 Y 坐标（按实际几何位置对应）
            val sideTapFrac = when (position) {
                TapPosition.BRIDGE, TapPosition.DIPOLE_L, TapPosition.DECOUPLE -> 0.62f
                TapPosition.DIPOLE_V -> 0.78f
                TapPosition.BACK -> 0.66f
            }
            val sideTapY = sideBodyTop + sideBodyH * sideTapFrac
            val sideTapPt = Offset(guitarRightX, sideTapY)

            // 侧视蓝色麦克风点（垂直于面板/背板 25 cm）
            val micSideX = splitX + rightW * 0.80f
            val micSidePt = Offset(micSideX, sideTapY)

            // 细微垂直辐射声波弧（直观示意垂直拾音方向，无粗蓝虚线）
            for (rFrac in listOf(0.28f, 0.48f)) {
                val r = (micSideX - guitarRightX) * rFrac
                drawArc(
                    color = Accent.copy(alpha = 0.30f),
                    startAngle = -32f,
                    sweepAngle = 64f,
                    useCenter = false,
                    topLeft = Offset(guitarRightX - r * 0.35f, sideTapY - r),
                    size = Size(r * 1.4f, r * 2f),
                    style = Stroke(width = 1.6f, cap = StrokeCap.Round)
                )
            }

            // 25 cm 水平标注尺（位于点位上方，干净工程尺寸线）
            val dimY = sideTapY - with(density) { 26.dp.toPx() }
            drawLine(
                color = TextMuted.copy(alpha = 0.75f),
                start = Offset(guitarRightX, dimY),
                end = Offset(micSideX, dimY),
                strokeWidth = 1.4f
            )
            drawLine(
                color = TextMuted.copy(alpha = 0.75f),
                start = Offset(guitarRightX, dimY - 5f),
                end = Offset(guitarRightX, dimY + 5f),
                strokeWidth = 1.4f
            )
            drawLine(
                color = TextMuted.copy(alpha = 0.75f),
                start = Offset(micSideX, dimY - 5f),
                end = Offset(micSideX, dimY + 5f),
                strokeWidth = 1.4f
            )

            // 离地 ≥80 cm 垂直高度标注尺
            val heightDimX = guitarLeftX + sideBodyW * 0.5f
            drawLine(
                color = TextMuted.copy(alpha = 0.75f),
                start = Offset(heightDimX, sideBodyBottom + 4f),
                end = Offset(heightDimX, groundY - 3f),
                strokeWidth = 1.4f
            )
            drawLine(
                color = TextMuted.copy(alpha = 0.75f),
                start = Offset(heightDimX - 5f, sideBodyBottom + 4f),
                end = Offset(heightDimX + 5f, sideBodyBottom + 4f),
                strokeWidth = 1.4f
            )
            drawLine(
                color = TextMuted.copy(alpha = 0.75f),
                start = Offset(heightDimX - 5f, groundY - 3f),
                end = Offset(heightDimX + 5f, groundY - 3f),
                strokeWidth = 1.4f
            )

            // 侧视橙色敲击点
            drawCircle(color = Warm.copy(alpha = 0.30f), radius = 10f, center = sideTapPt)
            drawCircle(color = Warm, radius = 5.5f, center = sideTapPt)

            // 侧视蓝色麦克风点（手机麦克风小圆点 + 光环）
            drawCircle(color = InfoBlue.copy(alpha = 0.24f), radius = 14f, center = micSidePt)
            drawCircle(color = InfoBlue, radius = 6.5f, center = micSidePt)
            drawCircle(color = Color.White, radius = 2.4f, center = micSidePt)

            // 尺寸数字文字（使用 nativeCanvas，字号克制且位于空白处）
            val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = InfoBlue.toArgb()
                textSize = with(density) { 10.sp.toPx() }
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TextMuted.toArgb()
                textSize = with(density) { 9.5.sp.toPx() }
            }
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                // 25 cm 标于尺寸线上方
                nc.drawText("25 cm", (guitarRightX + micSideX) * 0.5f, dimY - with(density) { 5.dp.toPx() }, dimPaint)
                // 离地 ≥80 cm 标于高度尺右侧
                mutedPaint.textAlign = Paint.Align.LEFT
                nc.drawText(
                    "离地 ≥80 cm",
                    heightDimX + with(density) { 6.dp.toPx() },
                    (sideBodyBottom + groundY) * 0.5f + with(density) { 3.5.dp.toPx() },
                    mutedPaint
                )
                // 右侧底部视角小标签
                mutedPaint.textAlign = Paint.Align.CENTER
                nc.drawText(
                    if (isBack) "侧视 · 背板对麦" else "侧视 · 面板对麦",
                    splitX + rightW * 0.50f,
                    h - with(density) { 2.dp.toPx() },
                    mutedPaint
                )
            }
        }
    }
}
