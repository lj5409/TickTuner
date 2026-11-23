package com.gtool.feature.tuner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Danger
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.TextMain
import java.util.Locale
import kotlin.math.roundToInt

// ------------------------------------------------------------------ 文案/数值

internal fun formatOneDecimal(value: Double): String =
    String.format(Locale.US, "%.1f", value)

internal fun formatCents(cents: Double?): String {
    if (cents == null) return "— ¢"
    val rounded = cents.roundToInt()
    val sign = if (rounded > 0) "+" else ""
    return "$sign$rounded ¢"
}

/** 弦钮标签内的紧凑音分（不带空格）。 */
internal fun formatCentsCompact(cents: Double): String {
    val rounded = cents.roundToInt()
    val sign = if (rounded > 0) "+" else ""
    return "$sign$rounded"
}

/** A4 标签：整数时省略小数。 */
internal fun formatA4(a4: Double): String =
    if (a4 % 1.0 == 0.0) a4.roundToInt().toString() else formatOneDecimal(a4)

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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 顶部调弦与参数单行：左侧当前调弦（点击切换），右侧 A4 / ♯♭ / 调弦管理。 */
@Composable
fun TunerSettingsRow(
    state: TunerUiState,
    onTuningClick: () -> Unit,
    onA4Click: () -> Unit,
    onFlatsToggle: () -> Unit,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TunerChip(
            text = state.tuning.name + " ▾",
            onClick = onTuningClick,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.weight(1f))
        TunerChip(text = "A4 ${formatA4(state.a4)}", onClick = onA4Click)
        TunerChip(text = if (state.useFlats) "♭" else "♯", onClick = onFlatsToggle)
        TunerChip(text = "调弦", onClick = onManageClick)
    }
}

/** 锁定弦后出现在右下角的参考音播放 / 停止按钮。 */
@Composable
fun ReferenceToneButton(
    playing: Boolean,
    noteName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (playing) Danger.copy(alpha = 0.2f) else Accent,
        border = if (playing) androidx.compose.foundation.BorderStroke(2.dp, Danger) else null,
        shadowElevation = 6.dp,
        modifier = modifier
            .size(60.dp)
            .semantics {
                contentDescription = if (playing) "停止参考音" else "播放 $noteName 参考音"
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (playing) {
                StopSquareIcon(color = Danger, sizeDp = 18)
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color(0xFF04211A),
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}

@Composable
internal fun StopSquareIcon(color: Color, sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}

// ------------------------------------------------------------------ 错误

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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
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
