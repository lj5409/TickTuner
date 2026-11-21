package com.gtool.feature.metronome

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gtool.core.data.MetronomeSettings
import com.gtool.core.device.TorchCapabilities
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Bg
import com.gtool.ui.theme.Danger
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.InfoBlue
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.Surface3
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val FlashColorPresets = listOf(
    0xFF2EE6A8L,
    0xFF4DA3FFL,
    0xFFFFB86BL,
    0xFFFF4D6DL,
    0xFFB388FFL,
    0xFFFFFFFFL
)

private data class TimeSignatureOption(val beats: Int, val unit: Int, val label: String)

private val TimeSignatureOptions = listOf(
    TimeSignatureOption(2, 4, "2/4"),
    TimeSignatureOption(3, 4, "3/4"),
    TimeSignatureOption(4, 4, "4/4"),
    TimeSignatureOption(6, 8, "6/8")
)

/**
 * 节拍器页面。
 *
 * 音频时钟由 [com.gtool.core.audio.ClickPlayer] 提供；画面、闪光灯、振动
 * 都对齐 [com.gtool.core.audio.BeatEvent.nanoTime]，不使用自计时 delay。
 */
@Composable
fun MetronomeScreen() {
    val vm: MetronomeViewModel = viewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val torchState by vm.torchState.collectAsStateWithLifecycle()
    val strongFlashAck by vm.strongFlashAck.collectAsStateWithLifecycle()
    val hintShown by vm.safetyHintShown.collectAsStateWithLifecycle()
    val previewBrightness by vm.previewBrightness.collectAsStateWithLifecycle()
    val previewColor by vm.previewColor.collectAsStateWithLifecycle()
    val previewTorchStrength by vm.torchStrengthPreview.collectAsStateWithLifecycle()

    val pulse = remember { BeatPulseState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var message by remember { mutableStateOf<String?>(null) }
    var showBpmInput by rememberSaveable { mutableStateOf(false) }
    var showStrongFlashConfirm by remember { mutableStateOf(false) }
    var avOffsetDraft by remember(settings.avOffsetMs) { mutableFloatStateOf(settings.avOffsetMs.toFloat()) }

    val flashFrequency = bpm * settings.subdivision / 60f
    val dangerous = flashFrequency > 3f
    val effectiveStyle = if (dangerous && settings.flashStyle == "flash" && !strongFlashAck) {
        "pulse"
    } else {
        settings.flashStyle
    }
    val effectiveBrightness = previewBrightness ?: settings.flashBrightness
    val effectiveColor = Color(previewColor ?: settings.flashColor)

    LaunchedEffect(vm) {
        vm.messages.collect { text -> message = text }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(4000)
            message = null
        }
    }

    // 退到后台或离开页面时停止播放并释放设备资源。
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.stop()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.stop()
        }
    }

    KeepScreenOn(playing)
    BeatVisualDriver(
        playing = playing,
        screenEnabled = settings.screenEnabled,
        flashStyle = effectiveStyle,
        avOffsetMs = avOffsetDraft.roundToInt(),
        brightness = effectiveBrightness,
        flashFrequencyHz = flashFrequency,
        pulse = pulse,
        events = vm.beatEvents
    )

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MessageBanner(message)

            SafetyBanner(
                dangerous = dangerous,
                frequency = flashFrequency,
                effectiveStyle = effectiveStyle,
                accentAllowed = settings.flashStyle == "flash" && effectiveStyle != "flash",
                onAllowStrongFlash = { showStrongFlashConfirm = true }
            )

            BpmSection(
                bpm = bpm,
                onBpmChange = { vm.setBpm(it) },
                onBpmCommit = { vm.setBpm(it, commit = true) },
                onTap = vm::tapTempo,
                onOpenInput = { showBpmInput = true }
            )

            BeatDots(settings = settings, pulse = pulse)

            TimeSignatureRow(
                beatsPerBar = settings.beatsPerBar,
                beatUnit = settings.beatUnit,
                onSelect = vm::setTimeSignature
            )

            SubdivisionRow(
                subdivision = settings.subdivision,
                onSelect = vm::setSubdivision
            )

            TransportRow(
                playing = playing,
                onToggle = vm::togglePlay,
                onNudge = vm::nudgeBpm
            )

            ModeRow(settings = settings, onToggle = vm::setMode)

            if (!settings.soundEnabled) {
                Text(
                    text = "静音模式：屏幕 / 闪光灯 / 振动打拍，不发声",
                    fontSize = 12.sp,
                    color = InfoBlue
                )
            }

            SettingsPanel(
                vm = vm,
                settings = settings,
                volume = volume,
                torchState = torchState,
                dangerous = dangerous,
                strongFlashAck = strongFlashAck,
                onRequestStrongFlash = { showStrongFlashConfirm = true },
                brightness = effectiveBrightness,
                previewStrength = previewTorchStrength ?: settings.torchStrength,
                avOffsetDraft = avOffsetDraft,
                onAvOffsetChange = { avOffsetDraft = it },
                onAvOffsetCommit = { vm.setAvOffset(avOffsetDraft.roundToInt()) }
            )

            Spacer(Modifier.height(8.dp))
        }

        // 覆盖层不消费触控事件，仅改变颜色与亮度。
        ScreenFlashOverlay(
            state = pulse,
            enabled = settings.screenEnabled,
            color = effectiveColor
        )
    }

    if (showBpmInput) {
        var text by remember { mutableStateOf(bpm.toString()) }
        AlertDialog(
            onDismissRequest = { showBpmInput = false },
            title = { Text("输入 BPM") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(3) },
                    singleLine = true,
                    label = { Text("30 – 300") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { vm.setBpm(it, commit = true) }
                    showBpmInput = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showBpmInput = false }) { Text("取消") }
            }
        )
    }

    if (settings.screenEnabled && !hintShown) {
        AlertDialog(
            onDismissRequest = { vm.dismissSafetyHint() },
            title = { Text("关于屏幕闪烁") },
            text = {
                Text(
                    "屏幕节拍会让屏幕随节拍变色、变亮。少数人可能因此出现头晕、眼疲劳等光敏性不适；" +
                        "如出现不适请立即停止使用。\n\n" +
                        "闪烁频率超过 3 Hz 时，应用会自动限制为柔和脉冲模式。"
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissSafetyHint() }) { Text("我知道了") }
            }
        )
    }

    if (showStrongFlashConfirm) {
        AlertDialog(
            onDismissRequest = { showStrongFlashConfirm = false },
            title = { Text("开启强闪？") },
            text = {
                Text(
                    "当前闪烁频率约 ${"%.1f".format(flashFrequency)} Hz，高于 3 Hz 的安全阈值。\n\n" +
                        "强闪可能引起光敏性不适，请确认你已了解风险，并避免长时间连续使用。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStrongFlashConfirm = false
                    vm.allowStrongFlash()
                    vm.setFlashStyle("flash")
                }) { Text("仍要开启") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStrongFlashConfirm = false
                    vm.setFlashStyle("pulse")
                }) { Text("取消") }
            }
        )
    }
}

// ------------------------------------------------------------------ 顶部 BPM

@Composable
private fun BpmSection(
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    onBpmCommit: (Int) -> Unit,
    onTap: () -> Unit,
    onOpenInput: () -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(bpm.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(bpm) {
        if (!dragging) sliderValue = bpm.toFloat()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = bpm.toString(),
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenInput() }
                        .padding(horizontal = 12.dp)
                )
                Text("BPM · 点击数字可直接输入", fontSize = 12.sp, color = TextMuted)
            }
            Column(
                modifier = Modifier.width(108.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedButton(
                    onClick = onTap,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("测速", fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("Tap Tempo · 连点 4 下", fontSize = 10.sp, color = TextMuted)
            }
        }

        Slider(
            value = sliderValue.coerceIn(30f, 300f),
            onValueChange = { value ->
                dragging = true
                sliderValue = value
                onBpmChange(value.roundToInt())
            },
            onValueChangeFinished = {
                dragging = false
                onBpmCommit(sliderValue.roundToInt())
            },
            valueRange = 30f..300f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ------------------------------------------------------------------ 拍点指示

@Composable
private fun BeatDots(settings: MetronomeSettings, pulse: BeatPulseState) {
    val beats = settings.beatsPerBar.coerceAtLeast(1)
    val absoluteBeat = pulse.activeBeat
    val current = if (absoluteBeat >= 0) Math.floorMod(absoluteBeat, beats) else -1

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (index in 0 until beats) {
                val level = settings.accentPattern.getOrElse(index) { if (index == 0) 2 else 1 }
                val isCurrent = index == current
                val baseSize = when (level) {
                    2 -> 28.dp
                    1 -> 21.dp
                    else -> 17.dp
                }
                val color = when {
                    isCurrent -> Accent
                    level == 2 -> Warm.copy(alpha = 0.8f)
                    level == 1 -> Surface3
                    else -> FretWire.copy(alpha = 0.55f)
                }
                val dotScale by animateFloatAsState(
                    targetValue = if (isCurrent) 1.25f else 1f,
                    animationSpec = tween(durationMillis = 110),
                    label = "beatDotScale"
                )
                // 固定尺寸槽位：缩放只影响绘制、不参与布局，避免每拍把下方 UI 顶动
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(baseSize)
                            .graphicsLayer {
                                scaleX = dotScale
                                scaleY = dotScale
                            }
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
        Text(
            text = if (current >= 0) "第 ${current + 1} 拍 / $beats" else "共 $beats 拍",
            fontSize = 12.sp,
            color = TextMuted
        )
    }
}

// ------------------------------------------------------------------ 拍号 / 细分

@Composable
private fun TimeSignatureRow(
    beatsPerBar: Int,
    beatUnit: Int,
    onSelect: (Int, Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TimeSignatureOptions.forEach { option ->
            FilterChip(
                selected = beatsPerBar == option.beats && beatUnit == option.unit,
                onClick = { onSelect(option.beats, option.unit) },
                label = { Text(option.label, fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                colors = metronomeChipColors()
            )
        }
    }
}

@Composable
private fun SubdivisionRow(subdivision: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("细分", fontSize = 13.sp, color = TextMuted, modifier = Modifier.width(36.dp))
        for (value in 1..4) {
            FilterChip(
                selected = subdivision == value,
                onClick = { onSelect(value) },
                label = { Text(if (value == 1) "1" else "$value", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                colors = metronomeChipColors()
            )
        }
    }
}

// ------------------------------------------------------------------ 播放控制

@Composable
private fun TransportRow(
    playing: Boolean,
    onToggle: () -> Unit,
    onNudge: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { onNudge(-1) },
            modifier = Modifier.size(width = 64.dp, height = 64.dp),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("−", fontSize = 28.sp, color = TextMain)
        }
        Button(
            onClick = onToggle,
            modifier = Modifier.weight(1f).height(64.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (playing) Surface3 else Accent,
                contentColor = if (playing) TextMain else Color(0xFF04211A)
            )
        ) {
            Text(
                text = if (playing) "暂停" else "开始",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        OutlinedButton(
            onClick = { onNudge(1) },
            modifier = Modifier.size(width = 64.dp, height = 64.dp),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("+", fontSize = 26.sp, color = TextMain)
        }
    }
}

// ------------------------------------------------------------------ 模式开关

@Composable
private fun ModeRow(
    settings: MetronomeSettings,
    onToggle: (MetronomeMode, Boolean) -> Unit
) {
    val items = listOf(
        Triple(MetronomeMode.SOUND, "声音", settings.soundEnabled),
        Triple(MetronomeMode.SCREEN, "屏幕", settings.screenEnabled),
        Triple(MetronomeMode.TORCH, "闪光灯", settings.torchEnabled),
        Triple(MetronomeMode.VIBRATE, "振动", settings.vibrateEnabled)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { (mode, label, selected) ->
            FilterChip(
                selected = selected,
                onClick = { onToggle(mode, !selected) },
                label = { Text(label, fontSize = 12.sp) },
                modifier = Modifier.weight(1f),
                colors = metronomeChipColors()
            )
        }
    }
}

// ------------------------------------------------------------------ 详细设置

@Composable
private fun SettingsPanel(
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
    onAvOffsetCommit: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        color = Surface1,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("详细设置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", fontSize = 13.sp, color = Accent)
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SliderRow(
                        label = "节拍音量",
                        value = volume,
                        range = 0f..1f,
                        valueText = "${(volume * 100).roundToInt()}%",
                        onValueChange = vm::setVolume
                    )

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
                            "本机不支持闪光灯亮度调节，已降级为开/关模式。",
                            fontSize = 12.sp,
                            color = Warm
                        )
                    }
                    if (capabilities != null && !capabilities.available) {
                        Text(
                            capabilities.error ?: "本机没有可用的闪光灯",
                            fontSize = 12.sp,
                            color = Danger
                        )
                    }

                    SliderRow(
                        label = "音画校准",
                        value = avOffsetDraft,
                        range = -100f..100f,
                        valueText = "${avOffsetDraft.roundToInt()} ms（正值画面提前）",
                        onValueChange = onAvOffsetChange,
                        onFinished = onAvOffsetCommit
                    )

                    AccentEditor(settings = settings, onCycle = vm::cycleAccent)
                }
            }
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
            horizontalArrangement = Arrangement.SpaceBetween
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
            onValueChangeFinished = onFinished
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
private fun AccentEditor(settings: MetronomeSettings, onCycle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("重音编辑（点击切换 重 / 中 / 静）", fontSize = 13.sp, color = TextMuted)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(settings.beatsPerBar.coerceAtLeast(1)) { index ->
                val level = settings.accentPattern.getOrElse(index) { if (index == 0) 2 else 1 }
                val label = when (level) {
                    2 -> "重"
                    1 -> "中"
                    else -> "静"
                }
                val background = when (level) {
                    2 -> Accent
                    1 -> Surface3
                    else -> Surface2
                }
                val contentColor = if (level == 2) Color(0xFF04211A) else TextMain
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(background)
                        .clickable { onCycle(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${index + 1}", fontSize = 11.sp, color = contentColor.copy(alpha = 0.75f))
                        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 提示条

@Composable
private fun MessageBanner(message: String?) {
    if (message == null) return
    Surface(
        color = Surface2,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            fontSize = 13.sp,
            color = InfoBlue
        )
    }
}

@Composable
private fun SafetyBanner(
    dangerous: Boolean,
    frequency: Float,
    effectiveStyle: String,
    accentAllowed: Boolean,
    onAllowStrongFlash: () -> Unit
) {
    if (!dangerous) return
    Surface(
        color = Warm.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "安全限制：闪烁约 ${"%.1f".format(frequency)} Hz（> 3 Hz）",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Warm
            )
            Text(
                text = if (effectiveStyle == "flash") {
                    "你已手动开启强闪，请控制使用时长；出现不适请立即停止。"
                } else {
                    "已自动限制为柔和脉冲。高频强闪可能引起光敏性不适。"
                },
                fontSize = 12.sp,
                color = TextMuted
            )
            if (accentAllowed) {
                TextButton(
                    onClick = onAllowStrongFlash,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("仍要强闪", fontSize = 12.sp, color = Accent)
                }
            }
        }
    }
}

@Composable
private fun metronomeChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Surface2,
    labelColor = TextMain,
    selectedContainerColor = Accent,
    selectedLabelColor = Color(0xFF04211A)
)




