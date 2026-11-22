package com.gtool.feature.acoustic

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
 * 自绘钢弦木吉他结构敲击引导图示：
 * - 步骤 1/2/3/5 绘制面板正面（琴颈、消音布缠弦、音孔、琴码、下腹），步骤 5 叠加音孔密封盖板
 * - 步骤 4 自动切换为背板背面（中线拼花与背板轮廓）
 * - 用脉冲靶心动态高亮当前需要敲击的物理拓扑点位与麦克风 25 cm 拾音方向
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

    val isBack = position == TapPosition.BACK
    val isDecouple = position == TapPosition.DECOUPLE

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, FretWire.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.44f
            val bodyTop = h * 0.16f
            val bodyBottom = h * 0.93f
            val bodyH = bodyBottom - bodyTop
            val maxHalfW = minOf(w * 0.32f, bodyH * 0.43f)
            val upperHalfW = maxHalfW * 0.78f
            val waistHalfW = maxHalfW * 0.62f

            // 琴颈（延伸至顶部）
            val neckW = maxHalfW * 0.25f
            drawRoundRect(
                color = Color(0xFF342A23),
                topLeft = Offset(cx - neckW / 2f, h * 0.02f),
                size = Size(neckW, bodyTop + bodyH * 0.20f),
                cornerRadius = CornerRadius(4f, 4f)
            )

            // 吉他箱体优雅八字形轮廓（左右对称三次贝塞尔曲线）
            val bodyPath = Path().apply {
                moveTo(cx, bodyTop)
                // 右上肩 -> 右腰 -> 右下腹 -> 底部中心
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
                // 左下腹 -> 左腰 -> 左上肩 -> 顶部中心
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
                    colors = listOf(Color(0xFF4F3124), Color(0xFF392118)),
                    startY = bodyTop,
                    endY = bodyBottom
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF8C6842), Color(0xFF6B4D2F)),
                    startY = bodyTop,
                    endY = bodyBottom
                )
            }
            drawPath(bodyPath, brush = woodBrush)
            drawPath(
                bodyPath,
                color = Color(0xFFD5B994).copy(alpha = 0.65f),
                style = Stroke(width = 3f)
            )

            val soundHoleCenter = Offset(cx, bodyTop + bodyH * 0.33f)
            val soundHoleR = maxHalfW * 0.25f
            val bridgeY = bodyTop + bodyH * 0.63f
            val bridgeW = maxHalfW * 0.86f
            val bridgeH = bodyH * 0.068f

            if (isBack) {
                // 背板中缝装饰条与横向背板音梁示意虚线
                drawLine(
                    color = Color(0xFFD6B995).copy(alpha = 0.55f),
                    start = Offset(cx, bodyTop + 4f),
                    end = Offset(cx, bodyBottom - 4f),
                    strokeWidth = 3f
                )
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                for (frac in listOf(0.28f, 0.52f, 0.76f)) {
                    val ly = bodyTop + bodyH * frac
                    val halfSpan = maxHalfW * (if (frac < 0.4f) 0.66f else 0.78f)
                    drawLine(
                        color = Color.White.copy(alpha = 0.14f),
                        start = Offset(cx - halfSpan, ly),
                        end = Offset(cx + halfSpan, ly),
                        strokeWidth = 2f,
                        pathEffect = dash
                    )
                }
            } else {
                // 面板音孔与音孔环饰
                drawCircle(
                    color = Color(0xFF1A1410),
                    radius = soundHoleR,
                    center = soundHoleCenter
                )
                drawCircle(
                    color = Color(0xFFE6CFAF).copy(alpha = 0.75f),
                    radius = soundHoleR * 1.13f,
                    center = soundHoleCenter,
                    style = Stroke(width = 2.4f)
                )

                // 步骤 5：音孔密封板覆盖示意
                if (isDecouple) {
                    drawCircle(
                        color = InfoBlue.copy(alpha = 0.38f),
                        radius = soundHoleR * 1.24f,
                        center = soundHoleCenter
                    )
                    drawCircle(
                        color = InfoBlue,
                        radius = soundHoleR * 1.24f,
                        center = soundHoleCenter,
                        style = Stroke(
                            width = 3f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                        )
                    )
                    // 叉线强调封堵
                    val cr = soundHoleR * 0.65f
                    drawLine(
                        color = InfoBlue,
                        start = Offset(soundHoleCenter.x - cr, soundHoleCenter.y - cr),
                        end = Offset(soundHoleCenter.x + cr, soundHoleCenter.y + cr),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = InfoBlue,
                        start = Offset(soundHoleCenter.x + cr, soundHoleCenter.y - cr),
                        end = Offset(soundHoleCenter.x - cr, soundHoleCenter.y + cr),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round
                    )
                }

                // 琴码
                drawRoundRect(
                    color = Color(0xFF2B201A),
                    topLeft = Offset(cx - bridgeW / 2f, bridgeY - bridgeH / 2f),
                    size = Size(bridgeW, bridgeH),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                // 下弦枕白条
                drawLine(
                    color = Color(0xFFF3EFEA),
                    start = Offset(cx - bridgeW * 0.36f, bridgeY - bridgeH * 0.14f),
                    end = Offset(cx + bridgeW * 0.36f, bridgeY - bridgeH * 0.08f),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )

                // 6 根琴弦 + 消音布条示意（强调琴弦全阻尼消音）
                for (s in 0 until 6) {
                    val t = (s - 2.5f) / 2.5f
                    val topX = cx + t * neckW * 0.36f
                    val botX = cx + t * bridgeW * 0.30f
                    drawLine(
                        color = Color(0xFFDCE6E1).copy(alpha = 0.45f),
                        start = Offset(topX, h * 0.03f),
                        end = Offset(botX, bridgeY + bridgeH * 0.16f),
                        strokeWidth = 1.4f
                    )
                }
                // 消音布条横穿在琴弦中段
                val muteY = bodyTop + bodyH * 0.12f
                drawRoundRect(
                    color = Accent.copy(alpha = 0.30f),
                    topLeft = Offset(cx - neckW * 0.65f, muteY - 7f),
                    size = Size(neckW * 1.3f, 14f),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                drawRoundRect(
                    color = Accent.copy(alpha = 0.75f),
                    topLeft = Offset(cx - neckW * 0.65f, muteY - 7f),
                    size = Size(neckW * 1.3f, 14f),
                    cornerRadius = CornerRadius(6f, 6f),
                    style = Stroke(width = 1.6f)
                )
            }

            // 计算当前敲击目标点坐标
            val targetPoint = when (position) {
                TapPosition.BRIDGE, TapPosition.DECOUPLE -> Offset(cx - bridgeW * 0.12f, bridgeY + bridgeH * 0.12f)
                TapPosition.DIPOLE_L -> Offset(cx - bridgeW * 0.44f, bridgeY)
                TapPosition.DIPOLE_V -> Offset(cx, bridgeY + bodyH * 0.15f)
                TapPosition.BACK -> Offset(cx, bodyTop + bodyH * 0.66f)
            }

            // 脉冲扩散环 + 实心高亮靶心
            val ringRadius = (12f + pulse * 26f)
            drawCircle(
                color = Warm.copy(alpha = (1f - pulse) * 0.75f),
                radius = ringRadius,
                center = targetPoint,
                style = Stroke(width = 3.5f)
            )
            drawCircle(
                color = Warm.copy(alpha = 0.25f),
                radius = 16f,
                center = targetPoint
            )
            drawCircle(
                color = Warm,
                radius = 7.5f,
                center = targetPoint
            )
            drawCircle(
                color = Color.White,
                radius = 3f,
                center = targetPoint
            )

            // 右侧绘制麦克风 25 cm 垂直指向示意
            val micX = w * 0.86f
            val micY = targetPoint.y
            drawLine(
                color = InfoBlue.copy(alpha = 0.65f),
                start = Offset(targetPoint.x + 18f, targetPoint.y),
                end = Offset(micX - 18f, micY),
                strokeWidth = 2.2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
            // 简易麦克风图标
            drawCircle(
                color = InfoBlue.copy(alpha = 0.22f),
                radius = 18f,
                center = Offset(micX, micY)
            )
            drawCircle(
                color = InfoBlue,
                radius = 8f,
                center = Offset(micX, micY)
            )
        }

        // 右上角姿态标签
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(10.dp))
                .background(Surface2.copy(alpha = 0.92f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = if (isBack) "背面视图（背板朝麦）" else if (isDecouple) "正面 · 封堵音孔" else "正面视图（琴弦消音）",
                color = if (isDecouple) InfoBlue else Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "● 敲击靶点",
                    color = Warm,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "● 麦距 25cm",
                    color = InfoBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 左上角步骤角标
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .clip(RoundedCornerShape(8.dp))
                .background(Surface2.copy(alpha = 0.9f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "${position.stepIndex}/5 · ${position.title}",
                color = TextMain,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 底部精简提示
        Text(
            text = "目标模态：${position.targetModesLabel}",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 2.dp)
        )
    }
}
