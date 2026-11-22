package com.gtool.feature.metronome

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.gtool.core.audio.BeatEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.exp

private const val FLASH_ON_SECONDS = 0.065f
private const val PULSE_RISE_SECONDS = 0.005f
private const val PULSE_DECAY_SECONDS = 0.11f
private const val OVERLAY_MAX_ALPHA = 0.9f

/**
 * 屏幕节拍的视觉状态。
 *
 * 由音频线程产生的 [BeatEvent] 入队，帧回调按 [BeatEvent.nanoTime]（加音画校准）
 * 决定何时点亮，绝不用 delay 自计时。
 */
internal class BeatPulseState {

    private val queue = ArrayDeque<BeatEvent>()

    /** 当前脉冲强度 0..1（只在 draw 阶段读取，避免每帧重组）。 */
    var intensity by mutableFloatStateOf(0f)
        private set

    /** 拍条视觉脉冲强度 0..1（静音拍与细分拍也有柔和反馈，不影响全屏闪拍）。 */
    var barIntensity by mutableFloatStateOf(0f)
        private set

    /** 当前拍在本小节的绝对序号（-1 = 未开始）。 */
    var activeBeat by mutableIntStateOf(-1)
        private set

    /** 当前细分序号（0 = 正拍）。 */
    var activeSub by mutableIntStateOf(0)
        private set

    /** 当前拍的重音等级。 */
    var activeLevel by mutableIntStateOf(0)
        private set

    var activeAccent by mutableStateOf(false)
        private set

    private var pulseStartNanos = 0L
    private var pulseLevel = 1
    private var pulseSub = 0
    private var primed = false

    fun enqueue(event: BeatEvent) {
        // 丢弃上一轮播放残留的过期拍点。
        if (event.nanoTime < System.nanoTime() - 300_000_000L) return
        if (queue.size < 64) queue.addLast(event)
    }

    fun reset() {
        queue.clear()
        intensity = 0f
        barIntensity = 0f
        activeBeat = -1
        activeSub = 0
        activeLevel = 0
        activeAccent = false
        pulseStartNanos = 0L
        primed = false
    }

    /** 每帧调用一次，返回当前强度。 */
    fun frame(frameNanos: Long, avOffsetMs: Int, style: String): Float {
        val offsetNanos = avOffsetMs.toLong() * 1_000_000L
        var due: BeatEvent? = null
        while (true) {
            val first = queue.firstOrNull() ?: break
            if (first.nanoTime - offsetNanos <= frameNanos) {
                due = queue.removeFirst()
            } else {
                break
            }
        }
        if (due != null) {
            pulseStartNanos = due.nanoTime - offsetNanos
            pulseLevel = due.level
            pulseSub = due.subIndex
            activeBeat = due.beatIndex
            activeSub = due.subIndex
            activeLevel = due.level
            activeAccent = due.isAccent
            primed = true
        }
        if (!primed) {
            if (intensity != 0f) intensity = 0f
            if (barIntensity != 0f) barIntensity = 0f
            return 0f
        }
        val elapsed = (frameNanos - pulseStartNanos) / 1_000_000_000f
        val levelScale = when {
            pulseLevel >= 2 -> 1f
            pulseLevel == 1 -> 0.62f
            else -> 0f
        }
        val subScale = if (pulseSub > 0) 0.45f else 1f
        val envelope = when {
            elapsed < 0f -> 0f
            style == "flash" -> if (elapsed < FLASH_ON_SECONDS) 1f else 0f
            elapsed < PULSE_RISE_SECONDS -> elapsed / PULSE_RISE_SECONDS
            else -> exp(-(elapsed - PULSE_RISE_SECONDS) / PULSE_DECAY_SECONDS)
        }
        val raw = envelope * levelScale * subScale
        val value = if (raw < 0.002f) 0f else raw.coerceAtMost(1f)
        if (value != intensity) intensity = value

        // 拍条视觉包络始终平滑衰减，静音拍与细分拍也提供轻量视觉反馈。
        val barEnvelope = when {
            elapsed < 0f -> 0f
            elapsed < PULSE_RISE_SECONDS -> elapsed / PULSE_RISE_SECONDS
            else -> exp(-(elapsed - PULSE_RISE_SECONDS) / 0.14f)
        }
        val barLevelScale = when {
            pulseLevel >= 2 -> 1f
            pulseLevel == 1 -> 0.85f
            else -> 0.45f
        }
        val barSubScale = if (pulseSub > 0) 0.55f else 1f
        val barRaw = barEnvelope * barLevelScale * barSubScale
        val barVal = if (barRaw < 0.002f) 0f else barRaw.coerceAtMost(1f)
        if (barVal != barIntensity) barIntensity = barVal
        return value
    }
}

/**
 * 全屏颜色覆盖层。没有 pointerInput，不会拦截触控。
 * 只在 draw 阶段读 [BeatPulseState.intensity]，脉冲时只重绘不重组。
 */
@Composable
internal fun ScreenFlashOverlay(
    state: BeatPulseState,
    enabled: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                if (!enabled) return@drawBehind
                val alpha = state.intensity * OVERLAY_MAX_ALPHA
                if (alpha > 0.004f) {
                    drawRect(color = color.copy(alpha = alpha))
                }
            }
    )
}

/**
 * 驱动屏幕节拍：收集拍点、逐帧计算脉冲、同步窗口亮度。
 *
 * - 亮度用 `window.attributes.screenBrightness`，不修改系统设置，退出时恢复。
 * - 闪烁频率 > 3 Hz 时不做窗口亮度脉冲（避免高频改窗口属性），只保留覆盖层。
 */
@Composable
internal fun BeatVisualDriver(
    playing: Boolean,
    screenEnabled: Boolean,
    flashStyle: String,
    avOffsetMs: Int,
    brightness: Float,
    flashFrequencyHz: Float,
    pulse: BeatPulseState,
    events: SharedFlow<BeatEvent>
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val brightnessController = remember(activity) {
        activity?.window?.let { window -> WindowBrightnessController(window) }
    }

    val screenEnabledState = rememberUpdatedState(screenEnabled)
    val styleState = rememberUpdatedState(flashStyle)
    val offsetState = rememberUpdatedState(avOffsetMs)
    val brightnessState = rememberUpdatedState(brightness)
    val windowPulseState = rememberUpdatedState(flashFrequencyHz <= 3f)

    // 页面存活期间一直订阅，避免按下“开始”瞬间的前 200 ms 拍点被丢掉。
    LaunchedEffect(events) {
        events.collect { event -> pulse.enqueue(event) }
    }

    LaunchedEffect(playing, brightnessController) {
        if (!playing) {
            pulse.reset()
            brightnessController?.restore()
            return@LaunchedEffect
        }
        // 注意：这里不要 reset，否则会丢掉按下“开始”后已经到达的前 200 ms 拍点。
        try {
            while (isActive) {
                withFrameNanos { frameNanos ->
                    val intensity = pulse.frame(frameNanos, offsetState.value, styleState.value)
                    val controller = brightnessController ?: return@withFrameNanos
                    if (!screenEnabledState.value) {
                        controller.restore()
                        return@withFrameNanos
                    }
                    val base = brightnessState.value
                    val target = if (windowPulseState.value) {
                        val minRatio = if (styleState.value == "flash") 0.10f else 0.30f
                        base * (minRatio + (1f - minRatio) * intensity)
                    } else {
                        base
                    }
                    controller.apply(target, frameNanos)
                }
            }
        } finally {
            brightnessController?.restore()
        }
    }

    DisposableEffect(brightnessController) {
        onDispose { brightnessController?.restore() }
    }
}

/** 运行中保持屏幕常亮（等价于 FLAG_KEEP_SCREEN_ON，View 会维护对应 window flags）。 */
@Composable
internal fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = previous }
    }
}

/**
 * 窗口亮度控制器。直接改 `WindowManager.LayoutParams.screenBrightness`，
 * 不改系统亮度；[restore] 会恢复进入页面前的值（-1 表示跟随系统）。
 */
internal class WindowBrightnessController(private val window: Window) {

    private val original: Float = window.attributes.screenBrightness
    private var applied: Float = original
    private var lastApplyNanos: Long = 0L

    fun apply(value: Float, nowNanos: Long) {
        if (lastApplyNanos != 0L && nowNanos - lastApplyNanos < 40_000_000L) return
        val target = value.coerceIn(0.01f, 1f)
        if (abs(target - applied) < 0.02f) return
        lastApplyNanos = nowNanos
        write(target)
    }

    fun restore() {
        if (applied == original) return
        write(original)
    }

    private fun write(value: Float) {
        runCatching {
            val attributes = window.attributes
            attributes.screenBrightness = value
            window.attributes = attributes
            applied = value
        }
    }
}

/** 从 Compose 的 Context 里找宿主 Activity。 */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
