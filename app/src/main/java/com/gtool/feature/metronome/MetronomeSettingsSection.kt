package com.gtool.feature.metronome

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtool.core.data.MetronomeSettings
import com.gtool.core.device.TorchCapabilities
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Danger
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.InfoBlue
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.Surface3
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm
import kotlin.math.roundToInt

private val FlashColorPresets = listOf(
    0xFF2EE6A8L,
    0xFF4DA3FFL,
    0xFFFFB86BL,
    0xFFFF4D6DL,
    0xFFB388FFL,
    0xFFFFFFFFL
)

/**
 * 节拍器详细设置区（默认直接展开，分为三张深色卡片）。
 */
@Composable
internal fun MetronomeSettingsSection(
    vm: MetronomeViewModel,
    settings: MetronomeSettings,
    volume: Float,
    torchState: TorchCapabilities?,
    dangerous: Boolean,
    strongFlashAck: Boolean,
    onRequestStrongFlash: () -> Unit,
    brightness: Float,
    previewStrength: Int,
    avOffsetDraft: Float,
    onAvOffsetChange: (Float) -> Unit,
    onAvOffsetCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 卡片 1：节奏与轻重拍
        SettingsCard(title = "节奏与轻重拍") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("拍号", fontSize = 13.sp, color = TextMuted)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TimeSignatureOptions.forEach { option ->
                        SegmentedOptionPill(
                            label = option.label,
                            selected = settings.beatsPerBar == option.beats && settings.beatUnit == option.unit,
                            onClick = { vm.setTimeSignature(option.beats, option.unit) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("细分", fontSize = 13.sp, color = TextMuted)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (value in 1..4) {
                        SegmentedOptionPill(
                            label = "$value",
                            selected = settings.subdivision == value,
                            onClick = { vm.setSubdivision(value) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            AccentEditor(settings = settings, onCycle = vm::cycleAccent)
        }

        // 卡片 2：输出模式与音量
        SettingsCard(title = "输出模式与音量") {
            val modeItems = listOf(
                Triple(MetronomeMode.SOUND, "声音", settings.soundEnabled),
                Triple(MetronomeMode.SCREEN, "屏幕", settings.screenEnabled),
                Triple(MetronomeMode.TORCH, "闪光灯", settings.torchEnabled),
                Triple(MetronomeMode.VIBRATE, "振动", settings.vibrateEnabled)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                modeItems.forEach { (mode, label, selected) ->
                    SegmentedOptionPill(
                        label = label,
                        selected = selected,
                        onClick = { vm.setMode(mode, !selected) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (!settings.soundEnabled) {
                Text(
                    text = "静音模式：屏幕 / 闪光灯 / 振动打拍，不发声",
                    fontSize = 12.sp,
                    color = InfoBlue
                )
            }

            SliderRow(
                label = "节拍音量",
                value = volume,
                range = 0f..1f,
                valueText = "${(volume * 100).roundToInt()}%",
                onValueChange = vm::setVolume
            )
        }

        // 卡片 3：屏幕闪拍 · 灯光 · 音画校准
        SettingsCard(title = "屏幕闪拍 · 灯光 · 音画校准") {
            SliderRow(
                label = "屏幕亮度",
                value = brightness,
                range = 0.1f..1f,
                valueText = "${(brightness * 100).roundToInt()}%",
                onValueChange = vm::previewFlashBrightness,
                onFinished = { vm.commitFlashBrightness(brightness) }
            )

            ColorSection(
                settings = settings,
                onPreview = vm::previewFlashColor,
                onCommit = vm::commitFlashColor,
                onPreset = vm::setFlashColor
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("屏幕闪烁风格", fontSize = 13.sp, color = TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.flashStyle == "pulse",
                        onClick = { vm.setFlashStyle("pulse") },
                        label = { Text("柔和脉冲", fontSize = 12.sp) },
                        colors = metronomeChipColors()
                    )
                    FilterChip(
                        selected = settings.flashStyle == "flash",
                        onClick = {
                            if (dangerous && !strongFlashAck) {
                                onRequestStrongFlash()
                            } else {
                                vm.setFlashStyle("flash")
                            }
                        },
                        label = { Text("强闪", fontSize = 12.sp) },
                        colors = metronomeChipColors()
                    )
                }
            }

            val capabilities = torchState
            val maxStrength = capabilities?.maxStrengthLevel?.coerceAtLeast(1) ?: 1
            SliderRow(
                label = "闪光灯强度",
                value = previewStrength.toFloat().coerceIn(1f, maxStrength.toFloat().coerceAtLeast(1f)),
                range = 1f..maxStrength.coerceAtLeast(2).toFloat(),
                valueText = if (capabilities?.supportsStrengthControl == true) {
                    "$previewStrength / $maxStrength"
                } else {
                    "不支持调节"
                },
                enabled = capabilities?.supportsStrengthControl == true,
                onValueChange = { vm.previewTorchStrength(it.roundToInt()) },
                onFinished = { vm.commitTorchStrength(previewStrength) }
            )
            if (capabilities != null && capabilities.available && !capabilities.supportsStrengthControl) {
                Text(
                    text = "本机不支持闪光灯亮度调节，已降级为开/关模式。",
                    fontSize = 12.sp,
                    color = Warm
                )
            }
            if (capabilities != null && !capabilities.available) {
                Text(
                    text = capabilities.error ?: "本机没有可用的闪光灯",
                    fontSize = 12.sp,
                    color = Danger
                )
            }

            val offsetInt = avOffsetDraft.roundToInt()
            val offsetPrefix = if (offsetInt > 0) "+$offsetInt" else "$offsetInt"
            SliderRow(
                label = "音画校准",
                value = avOffsetDraft,
                range = -100f..100f,
                valueText = "$offsetPrefix ms（正值画面提前）",
                onValueChange = onAvOffsetChange,
                onFinished = onAvOffsetCommit
            )
        }
    }
}

@Composable
private fun SegmentedOptionPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = if (selected) Accent else Surface2,
        shape = RoundedCornerShape(10.dp),
        border = if (selected) null else BorderStroke(1.dp, FretWire.copy(alpha = 0.7f)),
        modifier = modifier.height(40.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) Color(0xFF04211A) else TextMain,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = Surface1,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, FretWire.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMain
            )
            content()
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onFinished: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 13.sp, color = TextMuted)
            Text(valueText, fontSize = 12.sp, color = if (enabled) TextMain else TextMuted)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            valueRange = range,
            onValueChangeFinished = onFinished,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Surface3
            )
        )
    }
}

@Composable
private fun ColorSection(
    settings: MetronomeSettings,
    onPreview: (Long) -> Unit,
    onCommit: (Long) -> Unit,
    onPreset: (Long) -> Unit
) {
    val baseColor = settings.flashColor
    val hsv = remember(baseColor) {
        FloatArray(3).also { AndroidColor.colorToHSV(baseColor.toInt(), it) }
    }
    var hue by remember(baseColor) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(baseColor) { mutableFloatStateOf(hsv[1]) }

    fun composeColor(): Long =
        AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f)).toLong() and 0xFFFF_FFFFL

    val preview = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f)))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("闪烁颜色", fontSize = 13.sp, color = TextMuted)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(preview)
                    .border(1.dp, TextMuted, CircleShape)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlashColorPresets.chunked(3).forEach { rowColors ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowColors.forEach { preset ->
                        val selected = (preset and 0xFFFFFFL) == (baseColor and 0xFFFFFFL)
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(preset))
                                .then(
                                    if (selected) {
                                        Modifier.border(3.dp, TextMain, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { onPreset(preset) }
                        )
                    }
                }
            }
        }
        SliderRow(
            label = "色相",
            value = hue,
            range = 0f..360f,
            valueText = "${hue.roundToInt()}°",
            onValueChange = {
                hue = it
                onPreview(composeColor())
            },
            onFinished = { onCommit(composeColor()) }
        )
        SliderRow(
            label = "饱和度",
            value = saturation,
            range = 0f..1f,
            valueText = "${(saturation * 100).roundToInt()}%",
            onValueChange = {
                saturation = it
                onPreview(composeColor())
            },
            onFinished = { onCommit(composeColor()) }
        )
    }
}

@Composable
private fun AccentEditor(
    settings: MetronomeSettings,
    onCycle: (Int) -> Unit
) {
    val beatCount = settings.beatsPerBar.coerceAtLeast(1)
    val rows = (0 until beatCount).chunked(6)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("轻重拍编辑（点击切换 重 / 中 / 静）", fontSize = 13.sp, color = TextMuted)
        rows.forEach { rowIndices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowIndices.forEach { index ->
                    val level = settings.accentPattern.getOrElse(index) { if (index == 0) 2 else 1 }
                    val label = when (level) {
                        2 -> "重"
                        1 -> "中"
                        else -> "静"
                    }
                    val background = when (level) {
                        2 -> Warm
                        1 -> Accent
                        else -> Surface2
                    }
                    val contentColor = when (level) {
                        2 -> Color(0xFF2A1600)
                        1 -> Color(0xFF04211A)
                        else -> TextMuted
                    }
                    val isMuted = level == 0
                    val shape = RoundedCornerShape(12.dp)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 58.dp)
                            .clip(shape)
                            .background(background)
                            .then(
                                if (isMuted) {
                                    Modifier.drawBehind {
                                        val strokeWidth = 1.5.dp.toPx()
                                        val half = strokeWidth / 2f
                                        val corner = (12.dp.toPx() - half).coerceAtLeast(0f)
                                        drawRoundRect(
                                            color = FretWire,
                                            topLeft = Offset(half, half),
                                            size = Size(size.width - strokeWidth, size.height - strokeWidth),
                                            cornerRadius = CornerRadius(corner, corner),
                                            style = Stroke(
                                                width = strokeWidth,
                                                pathEffect = PathEffect.dashPathEffect(
                                                    floatArrayOf(10f, 8f),
                                                    0f
                                                )
                                            )
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onCycle(index) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    lineHeight = 13.sp,
                                    color = contentColor.copy(alpha = 0.78f),
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                                ),
                                maxLines = 1
                            )
                            Text(
                                text = label,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = contentColor,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
