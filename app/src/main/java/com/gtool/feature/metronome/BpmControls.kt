package com.gtool.feature.metronome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.AccentDim
import com.gtool.ui.theme.Bg
import com.gtool.ui.theme.Danger
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.Surface1
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.Surface3
import com.gtool.ui.theme.TextMain
import com.gtool.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class TimeSignatureOption(
    val beats: Int,
    val unit: Int,
    val label: String
)

internal val TimeSignatureOptions = listOf(
    TimeSignatureOption(2, 4, "2/4"),
    TimeSignatureOption(3, 4, "3/4"),
    TimeSignatureOption(4, 4, "4/4"),
    TimeSignatureOption(6, 8, "6/8")
)

@Composable
internal fun metronomeChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Surface2,
    labelColor = TextMain,
    selectedContainerColor = Accent,
    selectedLabelColor = Color(0xFF04211A)
)

// ------------------------------------------------------------------ BPM 数字小键盘状态逻辑

internal object BpmNumpadLogic {
    data class State(
        val text: String,
        val replaceOnFirstDigit: Boolean = true
    )

    fun initial(currentBpm: Int): State = State(
        text = currentBpm.toString(),
        replaceOnFirstDigit = true
    )

    fun inputDigit(state: State, digit: Char): State {
        if (!digit.isDigit()) return state
        if (state.replaceOnFirstDigit) {
            return State(text = digit.toString(), replaceOnFirstDigit = false)
        }
        if (state.text == "0") {
            return State(text = digit.toString(), replaceOnFirstDigit = false)
        }
        if (state.text.length >= 3) {
            return state.copy(replaceOnFirstDigit = false)
        }
        return State(text = state.text + digit, replaceOnFirstDigit = false)
    }

    fun backspace(state: State): State = State(
        text = state.text.dropLast(1),
        replaceOnFirstDigit = false
    )

    fun clear(): State = State(text = "", replaceOnFirstDigit = false)

    fun parsedBpm(state: State): Int? {
        val value = state.text.toIntOrNull() ?: return null
        return value.takeIf { it in MetronomeViewModel.MIN_BPM..MetronomeViewModel.MAX_BPM }
    }
}

// ------------------------------------------------------------------ 自研数字键盘弹窗（不拉起系统输入法）

@Composable
internal fun BpmNumpadDialog(
    initialBpm: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var state by remember(initialBpm) { mutableStateOf(BpmNumpadLogic.initial(initialBpm)) }
    val parsed = BpmNumpadLogic.parsedBpm(state)
    val isOutOfRange = state.text.isNotEmpty() && parsed == null
    val haptics = LocalHapticFeedback.current
    val compactHeight = LocalConfiguration.current.screenHeightDp < 520
    val keyHeight = if (compactHeight) 44.dp else 52.dp
    val gridSpacing = if (compactHeight) 6.dp else 8.dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Surface1,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, FretWire),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .widthIn(max = 340.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = if (compactHeight) 14.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactHeight) 10.dp else 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "输入 BPM",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain
                    )
                    Text(
                        text = "${MetronomeViewModel.MIN_BPM} – ${MetronomeViewModel.MAX_BPM}",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }

                // 数值显示框
                Surface(
                    color = Bg,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (isOutOfRange) Danger else FretWire),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = if (compactHeight) 8.dp else 12.dp, horizontal = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = state.text.ifEmpty { "—" },
                            fontSize = if (compactHeight) 32.sp else 40.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                isOutOfRange -> Danger
                                state.text.isEmpty() -> TextMuted
                                else -> Accent
                            },
                            maxLines = 1
                        )
                        Text(
                            text = when {
                                isOutOfRange -> "范围需在 ${MetronomeViewModel.MIN_BPM} – ${MetronomeViewModel.MAX_BPM} 之间"
                                state.replaceOnFirstDigit -> "按数字键可直接重新输入"
                                else -> " "
                            },
                            fontSize = 11.sp,
                            color = if (isOutOfRange) Danger else TextMuted
                        )
                    }
                }

                // 3×4 数字键盘
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("清空", "0", "⌫")
                )
                Column(verticalArrangement = Arrangement.spacedBy(gridSpacing)) {
                    rows.forEach { rowKeys ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing)
                        ) {
                            rowKeys.forEach { label ->
                                val isAction = label == "清空" || label == "⌫"
                                Surface(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        state = when (label) {
                                            "清空" -> BpmNumpadLogic.clear()
                                            "⌫" -> BpmNumpadLogic.backspace(state)
                                            else -> BpmNumpadLogic.inputDigit(state, label[0])
                                        }
                                    },
                                    color = if (isAction) Surface3 else Surface2,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, FretWire.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(keyHeight)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = label,
                                            fontSize = if (label == "清空") 14.sp else 20.sp,
                                            fontWeight = if (isAction) FontWeight.Medium else FontWeight.SemiBold,
                                            color = if (label == "清空") TextMuted else TextMain
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 底部确认/取消行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, FretWire)
                    ) {
                        Text("取消", fontSize = 15.sp, color = TextMain)
                    }
                    Button(
                        onClick = { parsed?.let(onConfirm) },
                        enabled = parsed != null,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color(0xFF04211A),
                            disabledContainerColor = Surface3,
                            disabledContentColor = TextMuted
                        )
                    ) {
                        Text("确定", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 竖屏 / 折叠屏左栏 / 悬停下半屏 BPM 控制区

@Composable
internal fun BpmControlBlock(
    bpm: Int,
    beatsPerBar: Int,
    beatUnit: Int,
    onBpmChange: (Int) -> Unit,
    onBpmCommit: (Int) -> Unit,
    onNudge: (Int) -> Unit,
    onTap: () -> Unit,
    onOpenNumpad: () -> Unit,
    onSelectTimeSignature: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderValue by remember { mutableFloatStateOf(bpm.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(bpm) {
        if (!dragging) sliderValue = bpm.toFloat()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 顶部：超大 BPM 数字 + 右侧 Tap 测速
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenNumpad)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = bpm.toString(),
                    fontSize = 68.sp,
                    lineHeight = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                    maxLines = 1,
                    softWrap = false,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
                Text(
                    text = "BPM · 点击输入",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedButton(
                    onClick = onTap,
                    modifier = Modifier.height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Accent.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Accent.copy(alpha = 0.10f),
                        contentColor = Accent
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Tap 测速",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                Text(
                    text = "连点 4 下",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }

        // 中间：横向 BPM 滑块
        Slider(
            value = sliderValue.coerceIn(
                MetronomeViewModel.MIN_BPM.toFloat(),
                MetronomeViewModel.MAX_BPM.toFloat()
            ),
            onValueChange = { value ->
                dragging = true
                sliderValue = value
                onBpmChange(value.roundToInt())
            },
            onValueChangeFinished = {
                dragging = false
                onBpmCommit(sliderValue.roundToInt())
            },
            valueRange = MetronomeViewModel.MIN_BPM.toFloat()..MetronomeViewModel.MAX_BPM.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Surface3
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // 底部：[ − ]  [ 4/4 ]  [ + ]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NudgeHoldButton(
                label = "−",
                contentDescription = "降低 BPM",
                delta = -1,
                bpm = bpm,
                onBpmChange = onBpmChange,
                onBpmCommit = onBpmCommit,
                onNudge = onNudge,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            )
            TimeSignaturePillButton(
                beatsPerBar = beatsPerBar,
                beatUnit = beatUnit,
                onSelect = onSelectTimeSignature,
                modifier = Modifier
                    .weight(1.15f)
                    .height(54.dp)
            )
            NudgeHoldButton(
                label = "+",
                contentDescription = "提高 BPM",
                delta = 1,
                bpm = bpm,
                onBpmChange = onBpmChange,
                onBpmCommit = onBpmCommit,
                onNudge = onNudge,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            )
        }
    }
}

@Composable
private fun TimeSignaturePillButton(
    beatsPerBar: Int,
    beatUnit: Int,
    onSelect: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            color = Surface2,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, FretWire),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "$beatsPerBar/$beatUnit",
                    fontSize = 20.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMain,
                    maxLines = 1
                )
                Text(
                    text = "拍号 ▾",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = TextMuted
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Surface2
        ) {
            TimeSignatureOptions.forEach { option ->
                val isSelected = beatsPerBar == option.beats && beatUnit == option.unit
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = option.label,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Accent else TextMain
                            )
                            if (isSelected) {
                                Text(
                                    text = "✓",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Accent
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option.beats, option.unit)
                    }
                )
            }
        }
    }
}

@Composable
private fun NudgeHoldButton(
    label: String,
    contentDescription: String,
    delta: Int,
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    onBpmCommit: (Int) -> Unit,
    onNudge: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentBpm by rememberUpdatedState(bpm)
    val currentOnBpmChange by rememberUpdatedState(onBpmChange)
    val currentOnBpmCommit by rememberUpdatedState(onBpmCommit)
    val currentOnNudge by rememberUpdatedState(onNudge)
    val interactionSource = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(Surface2)
            .border(1.dp, FretWire, shape)
            .indication(interactionSource, ripple())
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick {
                    currentOnNudge(delta)
                    true
                }
            }
            .pointerInput(delta) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val press = PressInteraction.Press(down.position)
                    scope.launch { interactionSource.emit(press) }

                    var repeating = false
                    var localBpm = currentBpm
                    val repeatJob = scope.launch {
                        delay(380L)
                        repeating = true
                        while (isActive) {
                            localBpm = (localBpm + delta).coerceIn(
                                MetronomeViewModel.MIN_BPM,
                                MetronomeViewModel.MAX_BPM
                            )
                            currentOnBpmChange(localBpm)
                            delay(85L)
                        }
                    }

                    val up = waitForUpOrCancellation()
                    repeatJob.cancel()
                    scope.launch {
                        interactionSource.emit(
                            if (up != null) PressInteraction.Release(press)
                            else PressInteraction.Cancel(press)
                        )
                    }

                    if (repeating) {
                        currentOnBpmCommit(localBpm)
                    } else if (up != null) {
                        currentOnNudge(delta)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMain
        )
    }
}

// ------------------------------------------------------------------ 横屏 / 帐篷模式：竖向滑块与右侧面板

private fun bpmToFraction(bpm: Int): Float =
    ((bpm - MetronomeViewModel.MIN_BPM).toFloat() /
        (MetronomeViewModel.MAX_BPM - MetronomeViewModel.MIN_BPM).toFloat()).coerceIn(0f, 1f)

private fun fractionToBpm(fraction: Float): Int =
    (MetronomeViewModel.MIN_BPM +
        fraction.coerceIn(0f, 1f) * (MetronomeViewModel.MAX_BPM - MetronomeViewModel.MIN_BPM))
        .roundToInt()
        .coerceIn(MetronomeViewModel.MIN_BPM, MetronomeViewModel.MAX_BPM)

@Composable
internal fun VerticalBpmSlider(
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    onBpmCommit: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var localFraction by remember { mutableFloatStateOf(bpmToFraction(bpm)) }
    var dragging by remember { mutableStateOf(false) }
    val currentOnBpmChange by rememberUpdatedState(onBpmChange)
    val currentOnBpmCommit by rememberUpdatedState(onBpmCommit)

    LaunchedEffect(bpm) {
        if (!dragging) {
            localFraction = bpmToFraction(bpm)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = MetronomeViewModel.MAX_BPM.toString(),
            fontSize = 11.sp,
            color = TextMuted
        )

        Box(
            modifier = Modifier
                .width(44.dp)
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val pad = 12.dp.toPx()
                        fun yToFraction(y: Float): Float {
                            val usable = (size.height - 2f * pad).coerceAtLeast(1f)
                            return 1f - ((y - pad) / usable).coerceIn(0f, 1f)
                        }

                        dragging = true
                        var fraction = yToFraction(down.position.y)
                        localFraction = fraction
                        var lastBpm = fractionToBpm(fraction)
                        currentOnBpmChange(lastBpm)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            fraction = yToFraction(change.position.y)
                            localFraction = fraction
                            val nextBpm = fractionToBpm(fraction)
                            if (nextBpm != lastBpm) {
                                lastBpm = nextBpm
                                currentOnBpmChange(nextBpm)
                            }
                        }

                        dragging = false
                        currentOnBpmCommit(fractionToBpm(localFraction))
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.width / 2f
                val corner = CornerRadius(radius, radius)
                val pad = 12.dp.toPx().coerceAtMost(size.height / 4f)
                val usable = (size.height - 2f * pad).coerceAtLeast(1f)
                val thumbY = pad + (1f - localFraction) * usable

                // 轨道底色
                drawRoundRect(
                    color = Surface2,
                    cornerRadius = corner
                )

                // 刻度线（60 / 120 / 180 / 240）
                val tickBpms = listOf(60, 120, 180, 240)
                val tickMargin = size.width * 0.24f
                tickBpms.forEach { tick ->
                    val y = pad + (1f - bpmToFraction(tick)) * usable
                    drawLine(
                        color = FretWire.copy(alpha = 0.75f),
                        start = Offset(tickMargin, y),
                        end = Offset(size.width - tickMargin, y),
                        strokeWidth = 1.2.dp.toPx()
                    )
                }

                // 底部向上的激活渐变填充
                clipRect(
                    left = 0f,
                    top = thumbY,
                    right = size.width,
                    bottom = size.height
                ) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Accent, AccentDim),
                            startY = thumbY,
                            endY = size.height
                        ),
                        cornerRadius = corner
                    )
                }

                // 白色横向游标条
                val thumbWidth = size.width * 0.62f
                val thumbHeight = 5.dp.toPx()
                val thumbLeft = (size.width - thumbWidth) / 2f
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(thumbLeft, thumbY - thumbHeight / 2f),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(thumbHeight / 2f, thumbHeight / 2f)
                )
            }

            // 轨道描边
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, FretWire, RoundedCornerShape(22.dp))
            )
        }

        Text(
            text = MetronomeViewModel.MIN_BPM.toString(),
            fontSize = 11.sp,
            color = TextMuted
        )
    }
}

@Composable
internal fun LandscapeBpmSidePanel(
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    onBpmCommit: (Int) -> Unit,
    onNudge: (Int) -> Unit,
    onTap: () -> Unit,
    onOpenNumpad: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VerticalBpmSlider(
            bpm = bpm,
            onBpmChange = onBpmChange,
            onBpmCommit = onBpmCommit,
            modifier = Modifier.fillMaxHeight()
        )

        Column(
            modifier = Modifier
                .width(118.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 顶部：可点击 BPM 数字
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface1)
                    .border(1.dp, FretWire, RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenNumpad)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = bpm.toString(),
                    fontSize = 44.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Bold,
                    color = Accent,
                    maxLines = 1,
                    softWrap = false,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
                Text(
                    text = "BPM",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            // + 按钮
            NudgeHoldButton(
                label = "+",
                contentDescription = "提高 BPM",
                delta = 1,
                bpm = bpm,
                onBpmChange = onBpmChange,
                onBpmCommit = onBpmCommit,
                onNudge = onNudge,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 48.dp)
            )

            // − 按钮
            NudgeHoldButton(
                label = "−",
                contentDescription = "降低 BPM",
                delta = -1,
                bpm = bpm,
                onBpmChange = onBpmChange,
                onBpmCommit = onBpmCommit,
                onNudge = onNudge,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 48.dp)
            )

            // Tap 按钮
            OutlinedButton(
                onClick = onTap,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.65f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Accent.copy(alpha = 0.10f),
                    contentColor = Accent
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = "Tap",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
