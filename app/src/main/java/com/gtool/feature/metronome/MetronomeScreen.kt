package com.gtool.feature.metronome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gtool.core.data.MetronomeSettings
import com.gtool.core.device.TorchCapabilities
import com.gtool.ui.AppPosture
import com.gtool.ui.LocalWindowPosture
import com.gtool.ui.WindowPostureState
import com.gtool.ui.theme.Accent
import com.gtool.ui.theme.Bg
import com.gtool.ui.theme.FretWire
import com.gtool.ui.theme.InfoBlue
import com.gtool.ui.theme.Surface2
import com.gtool.ui.theme.TextMuted
import com.gtool.ui.theme.Warm
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 节拍器页面。
 *
 * 音频时钟由 [com.gtool.core.audio.ClickPlayer] 提供；画面、闪光灯、振动
 * 都对齐 [com.gtool.core.audio.BeatEvent.nanoTime]，不使用自计时 delay。
 *
 * 根据 [LocalWindowPosture] 自动适配四种形态：
 * - 竖屏（手机 / 折叠屏外屏）：首屏「拍条舞台 + BPM 控制」，下滑展开全部设置；
 * - 横屏 / 帐篷模式：隐藏导航与设置，左侧竖直拍条 + 右侧竖向滑杆与 BPM/+/-/Tap；
 * - 悬停桌面模式（Tabletop）：上半屏纯拍条舞台，下半屏完整调节设置；
 * - 展开大屏（如 vivo X Fold3 Pro 内屏）：左右双栏（左主控 + 右常驻展开设置）。
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
    val tapStageHintShown by vm.tapStageHintShown.collectAsStateWithLifecycle()
    val previewBrightness by vm.previewBrightness.collectAsStateWithLifecycle()
    val previewColor by vm.previewColor.collectAsStateWithLifecycle()
    val previewTorchStrength by vm.torchStrengthPreview.collectAsStateWithLifecycle()

    val postureState = LocalWindowPosture.current
    val pulse = remember { BeatPulseState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var message by remember { mutableStateOf<String?>(null) }
    var showBpmNumpad by rememberSaveable { mutableStateOf(false) }
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
    val showTapHint = !tapStageHintShown

    val onToggleStage: () -> Unit = {
        if (showTapHint) vm.dismissTapStageHint()
        vm.togglePlay()
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        when (postureState.posture) {
            AppPosture.LANDSCAPE_COMPACT -> LandscapePerformLayout(
                settings = settings,
                bpm = bpm,
                playing = playing,
                pulse = pulse,
                showTapHint = showTapHint,
                message = message,
                onTogglePlay = onToggleStage,
                onDismissTapHint = vm::dismissTapStageHint,
                onBpmChange = { vm.setBpm(it) },
                onBpmCommit = { vm.setBpm(it, commit = true) },
                onNudge = vm::nudgeBpm,
                onTap = vm::tapTempo,
                onOpenNumpad = { showBpmNumpad = true }
            )

            AppPosture.TABLETOP -> TabletopMetronomeLayout(
                postureState = postureState,
                vm = vm,
                settings = settings,
                bpm = bpm,
                playing = playing,
                volume = volume,
                torchState = torchState,
                dangerous = dangerous,
                flashFrequency = flashFrequency,
                effectiveStyle = effectiveStyle,
                strongFlashAck = strongFlashAck,
                brightness = effectiveBrightness,
                previewStrength = previewTorchStrength ?: settings.torchStrength,
                avOffsetDraft = avOffsetDraft,
                onAvOffsetChange = { avOffsetDraft = it },
                onAvOffsetCommit = { vm.setAvOffset(avOffsetDraft.roundToInt()) },
                pulse = pulse,
                showTapHint = showTapHint,
                message = message,
                onTogglePlay = onToggleStage,
                onDismissTapHint = vm::dismissTapStageHint,
                onOpenNumpad = { showBpmNumpad = true },
                onRequestStrongFlash = { showStrongFlashConfirm = true }
            )

            AppPosture.UNFOLDED_EXPANDED -> UnfoldedDualPaneLayout(
                postureState = postureState,
                vm = vm,
                settings = settings,
                bpm = bpm,
                playing = playing,
                volume = volume,
                torchState = torchState,
                dangerous = dangerous,
                flashFrequency = flashFrequency,
                effectiveStyle = effectiveStyle,
                strongFlashAck = strongFlashAck,
                brightness = effectiveBrightness,
                previewStrength = previewTorchStrength ?: settings.torchStrength,
                avOffsetDraft = avOffsetDraft,
                onAvOffsetChange = { avOffsetDraft = it },
                onAvOffsetCommit = { vm.setAvOffset(avOffsetDraft.roundToInt()) },
                pulse = pulse,
                showTapHint = showTapHint,
                message = message,
                onTogglePlay = onToggleStage,
                onDismissTapHint = vm::dismissTapStageHint,
                onOpenNumpad = { showBpmNumpad = true },
                onRequestStrongFlash = { showStrongFlashConfirm = true }
            )

            AppPosture.PORTRAIT -> PortraitMetronomeLayout(
                vm = vm,
                settings = settings,
                bpm = bpm,
                playing = playing,
                volume = volume,
                torchState = torchState,
                dangerous = dangerous,
                flashFrequency = flashFrequency,
                effectiveStyle = effectiveStyle,
                strongFlashAck = strongFlashAck,
                brightness = effectiveBrightness,
                previewStrength = previewTorchStrength ?: settings.torchStrength,
                avOffsetDraft = avOffsetDraft,
                onAvOffsetChange = { avOffsetDraft = it },
                onAvOffsetCommit = { vm.setAvOffset(avOffsetDraft.roundToInt()) },
                pulse = pulse,
                showTapHint = showTapHint,
                message = message,
                onTogglePlay = onToggleStage,
                onDismissTapHint = vm::dismissTapStageHint,
                onOpenNumpad = { showBpmNumpad = true },
                onRequestStrongFlash = { showStrongFlashConfirm = true }
            )
        }

        // 覆盖层不消费触控事件，仅改变颜色与亮度。
        ScreenFlashOverlay(
            state = pulse,
            enabled = settings.screenEnabled,
            color = effectiveColor
        )
    }

    if (showBpmNumpad) {
        BpmNumpadDialog(
            initialBpm = bpm,
            onConfirm = { target ->
                vm.setBpm(target, commit = true)
                showBpmNumpad = false
            },
            onDismiss = { showBpmNumpad = false }
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

// ------------------------------------------------------------------ 1. 竖屏布局（首屏主控 + 下滑全部设置）

@Composable
private fun PortraitMetronomeLayout(
    vm: MetronomeViewModel,
    settings: MetronomeSettings,
    bpm: Int,
    playing: Boolean,
    volume: Float,
    torchState: TorchCapabilities?,
    dangerous: Boolean,
    flashFrequency: Float,
    effectiveStyle: String,
    strongFlashAck: Boolean,
    brightness: Float,
    previewStrength: Int,
    avOffsetDraft: Float,
    onAvOffsetChange: (Float) -> Unit,
    onAvOffsetCommit: () -> Unit,
    pulse: BeatPulseState,
    showTapHint: Boolean,
    message: String?,
    onTogglePlay: () -> Unit,
    onDismissTapHint: () -> Unit,
    onOpenNumpad: () -> Unit,
    onRequestStrongFlash: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val firstScreenHeight = maxHeight

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // 第一屏：精确占满视口高度，只保留拍条舞台与 BPM 控制区
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(firstScreenHeight)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MessageBanner(message)

                SafetyBanner(
                    dangerous = dangerous,
                    frequency = flashFrequency,
                    effectiveStyle = effectiveStyle,
                    accentAllowed = settings.flashStyle == "flash" && effectiveStyle != "flash",
                    onAllowStrongFlash = onRequestStrongFlash
                )

                HorizontalStackedBeatStage(
                    settings = settings,
                    pulse = pulse,
                    playing = playing,
                    showTapHint = showTapHint,
                    onTogglePlay = onTogglePlay,
                    onDismissTapHint = onDismissTapHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                BpmControlBlock(
                    bpm = bpm,
                    beatsPerBar = settings.beatsPerBar,
                    beatUnit = settings.beatUnit,
                    onBpmChange = { vm.setBpm(it) },
                    onBpmCommit = { vm.setBpm(it, commit = true) },
                    onNudge = vm::nudgeBpm,
                    onTap = vm::tapTempo,
                    onOpenNumpad = onOpenNumpad,
                    onSelectTimeSignature = vm::setTimeSignature
                )

                ScrollMoreCue()
            }

            // 第二屏及以下：下滑直接露出全部展开的设置卡片
            MetronomeSettingsSection(
                vm = vm,
                settings = settings,
                volume = volume,
                torchState = torchState,
                dangerous = dangerous,
                strongFlashAck = strongFlashAck,
                onRequestStrongFlash = onRequestStrongFlash,
                brightness = brightness,
                previewStrength = previewStrength,
                avOffsetDraft = avOffsetDraft,
                onAvOffsetChange = onAvOffsetChange,
                onAvOffsetCommit = onAvOffsetCommit,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp)
            )
        }
    }
}

@Composable
private fun ScrollMoreCue() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(FretWire)
        )
        Text(
            text = "上滑查看更多设置",
            fontSize = 11.sp,
            color = TextMuted
        )
    }
}

// ------------------------------------------------------------------ 2. 横屏 / 帐篷演奏模式

@Composable
private fun LandscapePerformLayout(
    settings: MetronomeSettings,
    bpm: Int,
    playing: Boolean,
    pulse: BeatPulseState,
    showTapHint: Boolean,
    message: String?,
    onTogglePlay: () -> Unit,
    onDismissTapHint: () -> Unit,
    onBpmChange: (Int) -> Unit,
    onBpmCommit: (Int) -> Unit,
    onNudge: (Int) -> Unit,
    onTap: () -> Unit,
    onOpenNumpad: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧主视觉区（约 2/3 – 3/4）：竖直药丸拍条左到右排列
            VerticalRowBeatStage(
                settings = settings,
                pulse = pulse,
                playing = playing,
                showTapHint = showTapHint,
                onTogglePlay = onTogglePlay,
                onDismissTapHint = onDismissTapHint,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            // 右侧控制区（约 1/4 – 1/3）：竖向 BPM 滑杆 + 最右 BPM / + / − / Tap
            LandscapeBpmSidePanel(
                bpm = bpm,
                onBpmChange = onBpmChange,
                onBpmCommit = onBpmCommit,
                onNudge = onNudge,
                onTap = onTap,
                onOpenNumpad = onOpenNumpad,
                modifier = Modifier.fillMaxHeight()
            )
        }

        // 浮层提示（如 Tap Tempo 测速结果），不挤占横屏布局
        if (message != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
            ) {
                Surface(
                    color = Surface2.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        color = InfoBlue
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 3. 折叠屏悬停桌面模式（Tabletop）

@Composable
private fun TabletopMetronomeLayout(
    postureState: WindowPostureState,
    vm: MetronomeViewModel,
    settings: MetronomeSettings,
    bpm: Int,
    playing: Boolean,
    volume: Float,
    torchState: TorchCapabilities?,
    dangerous: Boolean,
    flashFrequency: Float,
    effectiveStyle: String,
    strongFlashAck: Boolean,
    brightness: Float,
    previewStrength: Int,
    avOffsetDraft: Float,
    onAvOffsetChange: (Float) -> Unit,
    onAvOffsetCommit: () -> Unit,
    pulse: BeatPulseState,
    showTapHint: Boolean,
    message: String?,
    onTogglePlay: () -> Unit,
    onDismissTapHint: () -> Unit,
    onOpenNumpad: () -> Unit,
    onRequestStrongFlash: () -> Unit
) {
    val density = LocalDensity.current
    var containerTopInWindowPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                containerTopInWindowPx = coords.positionInWindow().y
            }
    ) {
        val hinge = postureState.hingeBounds
        val topPaneHeight = if (hinge != null && hinge.isHorizontal && hinge.topPx > 0) {
            val localTopPx = (hinge.topPx - containerTopInWindowPx).coerceAtLeast(0f)
            with(density) { localTopPx.toDp() }.coerceIn(140.dp, (maxHeight - 180.dp).coerceAtLeast(140.dp))
        } else {
            maxHeight * 0.46f
        }
        val hingeGap = if (hinge != null && hinge.isHorizontal) {
            with(density) { (hinge.bottomPx - hinge.topPx).coerceAtLeast(0).toDp() }.coerceAtLeast(16.dp)
        } else {
            16.dp
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // 上半屏（折痕以上）：纯净超大方案 B 拍条舞台
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topPaneHeight)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                HorizontalStackedBeatStage(
                    settings = settings,
                    pulse = pulse,
                    playing = playing,
                    showTapHint = showTapHint,
                    onTogglePlay = onTogglePlay,
                    onDismissTapHint = onDismissTapHint,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 物理铰链安全避让区
            Spacer(Modifier.height(hingeGap))

            // 下半屏（折痕以下，平放桌面）：完整 BPM 调节与全部设置（可滚动）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MessageBanner(message)

                SafetyBanner(
                    dangerous = dangerous,
                    frequency = flashFrequency,
                    effectiveStyle = effectiveStyle,
                    accentAllowed = settings.flashStyle == "flash" && effectiveStyle != "flash",
                    onAllowStrongFlash = onRequestStrongFlash
                )

                BpmControlBlock(
                    bpm = bpm,
                    beatsPerBar = settings.beatsPerBar,
                    beatUnit = settings.beatUnit,
                    onBpmChange = { vm.setBpm(it) },
                    onBpmCommit = { vm.setBpm(it, commit = true) },
                    onNudge = vm::nudgeBpm,
                    onTap = vm::tapTempo,
                    onOpenNumpad = onOpenNumpad,
                    onSelectTimeSignature = vm::setTimeSignature
                )

                MetronomeSettingsSection(
                    vm = vm,
                    settings = settings,
                    volume = volume,
                    torchState = torchState,
                    dangerous = dangerous,
                    strongFlashAck = strongFlashAck,
                    onRequestStrongFlash = onRequestStrongFlash,
                    brightness = brightness,
                    previewStrength = previewStrength,
                    avOffsetDraft = avOffsetDraft,
                    onAvOffsetChange = onAvOffsetChange,
                    onAvOffsetCommit = onAvOffsetCommit
                )

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ 4. 折叠屏完全展开大屏双栏（如 vivo X Fold3 Pro）

@Composable
private fun UnfoldedDualPaneLayout(
    postureState: WindowPostureState,
    vm: MetronomeViewModel,
    settings: MetronomeSettings,
    bpm: Int,
    playing: Boolean,
    volume: Float,
    torchState: TorchCapabilities?,
    dangerous: Boolean,
    flashFrequency: Float,
    effectiveStyle: String,
    strongFlashAck: Boolean,
    brightness: Float,
    previewStrength: Int,
    avOffsetDraft: Float,
    onAvOffsetChange: (Float) -> Unit,
    onAvOffsetCommit: () -> Unit,
    pulse: BeatPulseState,
    showTapHint: Boolean,
    message: String?,
    onTogglePlay: () -> Unit,
    onDismissTapHint: () -> Unit,
    onOpenNumpad: () -> Unit,
    onRequestStrongFlash: () -> Unit
) {
    val density = LocalDensity.current
    var containerLeftInWindowPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                containerLeftInWindowPx = coords.positionInWindow().x
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        val hinge = postureState.hingeBounds
        val verticalHinge = hinge?.takeIf {
            postureState.isVerticalBookFold && !it.isHorizontal && it.leftPx > 0
        }
        val hingeGap = if (verticalHinge != null) {
            with(density) { (verticalHinge.rightPx - verticalHinge.leftPx).coerceAtLeast(0).toDp() }.coerceAtLeast(18.dp)
        } else {
            16.dp
        }
        val leftPaneFixedWidth = if (verticalHinge != null) {
            val localLeftPx = (verticalHinge.leftPx - containerLeftInWindowPx).coerceAtLeast(0f)
            with(density) { localLeftPx.toDp() }.coerceIn(260.dp, (maxWidth - 260.dp).coerceAtLeast(260.dp))
        } else {
            null
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(hingeGap)
        ) {
            // 左栏：节拍条主舞台 + BPM 控制区
            Column(
                modifier = (if (leftPaneFixedWidth != null) {
                    Modifier.width(leftPaneFixedWidth)
                } else {
                    Modifier.weight(1.12f)
                }).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MessageBanner(message)

                SafetyBanner(
                    dangerous = dangerous,
                    frequency = flashFrequency,
                    effectiveStyle = effectiveStyle,
                    accentAllowed = settings.flashStyle == "flash" && effectiveStyle != "flash",
                    onAllowStrongFlash = onRequestStrongFlash
                )

                HorizontalStackedBeatStage(
                    settings = settings,
                    pulse = pulse,
                    playing = playing,
                    showTapHint = showTapHint,
                    onTogglePlay = onTogglePlay,
                    onDismissTapHint = onDismissTapHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                BpmControlBlock(
                    bpm = bpm,
                    beatsPerBar = settings.beatsPerBar,
                    beatUnit = settings.beatUnit,
                    onBpmChange = { vm.setBpm(it) },
                    onBpmCommit = { vm.setBpm(it, commit = true) },
                    onNudge = vm::nudgeBpm,
                    onTap = vm::tapTempo,
                    onOpenNumpad = onOpenNumpad,
                    onSelectTimeSignature = vm::setTimeSignature
                )
            }

            // 右栏：独立滚动的全部设置卡片
            Column(
                modifier = Modifier
                    .weight(0.88f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetronomeSettingsSection(
                    vm = vm,
                    settings = settings,
                    volume = volume,
                    torchState = torchState,
                    dangerous = dangerous,
                    strongFlashAck = strongFlashAck,
                    onRequestStrongFlash = onRequestStrongFlash,
                    brightness = brightness,
                    previewStrength = previewStrength,
                    avOffsetDraft = avOffsetDraft,
                    onAvOffsetChange = onAvOffsetChange,
                    onAvOffsetCommit = onAvOffsetCommit
                )
                Spacer(Modifier.height(12.dp))
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
