package com.gtool.feature.tuner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ 横轴音分指示器：轨迹缓冲与映射（纯逻辑，可 JVM 单测）

/** 音分所在区间：准（±[TunerViewModel.IN_TUNE_CENTS]）/ 偏高 / 偏低。 */
enum class CentsZone { IN_TUNE, SHARP, FLAT }

object TrailMath {
    /** 横轴单侧量程（音分）。 */
    const val RANGE_CENTS: Float = 50f

    /** 轨迹保留时长：超过该时长的点完全淡出。 */
    const val WINDOW_MS: Long = 3000L

    /** 相邻两点时间间隔超过该值视为断开（无信号），不连线。 */
    const val MAX_SEGMENT_GAP_MS: Long = 120L

    /** 把音分映射到横轴 0..1（超出量程钳在两端，0.5 为正中）。 */
    fun centsToFraction(cents: Float, range: Float = RANGE_CENTS): Float {
        if (cents.isNaN()) return 0.5f
        val clamped = cents.coerceIn(-range, range)
        return (clamped + range) / (2f * range)
    }

    /** 轨迹点按存活时长渐隐：0 ms 为 1，[windowMs] 及以上为 0，先慢后快（二次缓出）。 */
    fun ageAlpha(ageMs: Long, windowMs: Long = WINDOW_MS): Float {
        if (windowMs <= 0L) return 0f
        if (ageMs <= 0L) return 1f
        if (ageMs >= windowMs) return 0f
        val remain = 1f - ageMs.toFloat() / windowMs.toFloat()
        return remain * remain
    }

    /** 轨迹点的纵向位置 0..1（0 = 紧贴横轴的最新点，1 = 最老的点）。 */
    fun ageFraction(ageMs: Long, windowMs: Long = WINDOW_MS): Float {
        if (windowMs <= 0L) return 1f
        return (ageMs.toFloat() / windowMs.toFloat()).coerceIn(0f, 1f)
    }

    fun zoneOf(cents: Float, inTuneCents: Float = TunerViewModel.IN_TUNE_CENTS.toFloat()): CentsZone = when {
        abs(cents) <= inTuneCents -> CentsZone.IN_TUNE
        cents > 0f -> CentsZone.SHARP
        else -> CentsZone.FLAT
    }
}

/**
 * 音分轨迹环形缓冲（下标 0 = 最老）。只在 UI 帧回调里单线程读写，零分配。
 * - 无信号用 NaN 表示，连续无信号只记一个断点，空闲时缓冲会自然排空（停止重绘）；
 * - [push] 按 [minIntervalMs] 节流，避免高刷新率屏幕把容量撑满。
 */
class CentsTrail(capacity: Int = DEFAULT_CAPACITY) {
    private val times = LongArray(capacity.coerceAtLeast(2))
    private val values = FloatArray(capacity.coerceAtLeast(2))
    private var start = 0

    var size: Int = 0
        private set

    val capacity: Int get() = times.size

    /** 记录一个采样；返回是否真的写入。 */
    fun push(timeMs: Long, cents: Float?, minIntervalMs: Long = MIN_INTERVAL_MS): Boolean {
        val value = cents ?: Float.NaN
        if (size > 0) {
            val lastIndex = physical(size - 1)
            val lastValue = values[lastIndex]
            if (value.isNaN() && lastValue.isNaN()) return false
            if (!value.isNaN() && !lastValue.isNaN() && timeMs - times[lastIndex] < minIntervalMs) return false
        } else if (value.isNaN()) {
            return false
        }
        val index = physical(size)
        times[index] = timeMs
        values[index] = value
        if (size < capacity) size++ else start = (start + 1) % capacity
        return true
    }

    /** 丢弃已完全淡出（age >= windowMs）的点，以及排在队首的无信号断点。 */
    fun prune(nowMs: Long, windowMs: Long = TrailMath.WINDOW_MS) {
        while (size > 0 && (nowMs - times[start] >= windowMs || values[start].isNaN())) {
            start = (start + 1) % capacity
            size--
        }
    }

    fun timeAt(i: Int): Long = times[physical(i)]

    /** NaN 表示该时刻无信号。 */
    fun centsAt(i: Int): Float = values[physical(i)]

    fun clear() {
        start = 0
        size = 0
    }

    private fun physical(i: Int): Int = (start + i) % capacity

    companion object {
        const val MIN_INTERVAL_MS: Long = 12L
        val DEFAULT_CAPACITY: Int = (TrailMath.WINDOW_MS / MIN_INTERVAL_MS).toInt() + 32
    }
}

// ------------------------------------------------------------------ 琴头弦钮布局（纯逻辑，可 JVM 单测）

enum class PegSide { LEFT, RIGHT }

/** [row] = 0 表示最靠近琴枕（最下方）的弦钮。 */
data class PegSlot(val stringIndex: Int, val side: PegSide, val row: Int)

/**
 * 3+3 民谣琴头的弦钮分配（琴头朝上、正面视角）：
 * - 左侧（低音侧）：最低音弦（下标 0，6 弦）最靠近琴枕，向上依次 5、4 弦；
 * - 右侧（高音侧）：最高音弦（1 弦）最靠近琴枕，向上依次 2、3 弦。
 * 奇数弦数时左侧多一个。
 */
fun headstockPegSlots(stringCount: Int): List<PegSlot> {
    if (stringCount <= 0) return emptyList()
    val leftCount = (stringCount + 1) / 2
    return List(stringCount) { index ->
        if (index < leftCount) {
            PegSlot(index, PegSide.LEFT, index)
        } else {
            PegSlot(index, PegSide.RIGHT, stringCount - 1 - index)
        }
    }
}

data class PegGeometry(
    val stringIndex: Int,
    val side: PegSide,
    val row: Int,
    val y: Float,
    /** 弦轴（缠弦柱）圆心 x。 */
    val postX: Float,
    /** 琴头侧边 x（旋钮杆起点）。 */
    val edgeX: Float,
    /** 旋钮按键中心 x。 */
    val knobX: Float,
    /** 音高标签中心 x。 */
    val labelX: Float
)

/**
 * 琴头几何（单位与输入一致，UI 传入 dp）。整体（琴头 + 一截琴颈）在区域内居中，
 * 两侧为音高标签预留空间，保证标签不出界。
 */
data class HeadstockGeometry(
    val width: Float,
    val height: Float,
    val centerX: Float,
    val bodyTop: Float,
    val cornerY: Float,
    val nutY: Float,
    val neckBottom: Float,
    val topWidth: Float,
    val nutWidth: Float,
    val knobOut: Float,
    val knobWidth: Float,
    val knobHeight: Float,
    val postRadius: Float,
    val labelSize: Float,
    val logoTop: Float,
    val logoHeight: Float,
    val nutStringX: List<Float>,
    val pegs: List<PegGeometry>
) {
    /** 琴头在纵坐标 y 处的半宽（琴枕处最窄，顶角处最宽）。 */
    fun halfWidthAt(y: Float): Float {
        val span = (nutY - cornerY).coerceAtLeast(1f)
        val t = ((nutY - y) / span).coerceIn(0f, 1f)
        return nutWidth / 2f + (topWidth - nutWidth) / 2f * t
    }
}

/** 横屏专注模式下顶部调弦切换胶囊预留的顶部安全间距（dp），避免调弦按钮遮挡琴头。 */
const val HEADSTOCK_BADGE_TOP_INSET_DP: Float = 36f

fun computeHeadstockGeometry(
    width: Float,
    height: Float,
    stringCount: Int,
    topInset: Float = 0f
): HeadstockGeometry {
    val w = width.coerceAtLeast(120f)
    val h = height.coerceAtLeast(120f)
    val inset = topInset.coerceIn(0f, h * 0.25f)
    val availH = (h - inset).coerceAtLeast(96f)
    val slots = headstockPegSlots(stringCount.coerceAtLeast(1))
    val rows = (slots.maxOfOrNull { it.row } ?: 0) + 1

    val gap = 6f
    val neckRatio = 0.24f
    val heightBudget = availH * 0.96f / (1f + neckRatio)

    // 标签尺寸：受行距与宽度约束，保证 ≥ 40（接近 48 触控目标）
    val provisionalSpacing = heightBudget * 0.6f / max(rows - 1, 1)
    val labelSize = min(min(56f, provisionalSpacing * 0.92f), w * 0.16f).coerceAtLeast(40f)

    val widthLimit = (w - 2f * (gap + labelSize) - 8f) / 1.5f
    val topWidth = min(heightBudget * 0.52f, widthLimit).coerceAtLeast(56f)
    val bodyHeight = min(heightBudget, topWidth * 2.0f)
    val neckLength = (bodyHeight * neckRatio).coerceAtLeast(20f)
    val total = bodyHeight + neckLength
    val bodyTop = (inset + (availH - total) / 2f).coerceAtLeast(inset)
    val nutY = bodyTop + bodyHeight
    val cornerY = bodyTop + bodyHeight * 0.06f
    val nutWidth = topWidth * 0.58f
    val knobOut = topWidth * 0.25f
    val centerX = w / 2f

    val rowNearest = bodyHeight * 0.18f
    val rowSpan = bodyHeight * 0.60f
    val spacing = if (rows > 1) rowSpan / (rows - 1) else rowSpan
    val knobHeight = min(spacing * 0.5f, knobOut * 0.95f).coerceAtLeast(8f)

    val partial = HeadstockGeometry(
        width = w,
        height = h,
        centerX = centerX,
        bodyTop = bodyTop,
        cornerY = cornerY,
        nutY = nutY,
        neckBottom = nutY + neckLength,
        topWidth = topWidth,
        nutWidth = nutWidth,
        knobOut = knobOut,
        knobWidth = knobOut * 0.62f,
        knobHeight = knobHeight,
        postRadius = (topWidth * 0.055f).coerceIn(3f, 8f),
        labelSize = labelSize,
        logoTop = bodyTop + bodyHeight * 0.035f,
        logoHeight = (bodyHeight * 0.13f).coerceIn(22f, 40f),
        nutStringX = emptyList(),
        pegs = emptyList()
    )

    val count = stringCount.coerceAtLeast(1)
    val spread = nutWidth * 0.78f
    val nutStringX = List(count) { i ->
        if (count == 1) centerX else centerX - spread / 2f + spread * i / (count - 1)
    }

    val postInset = topWidth * 0.16f
    val pegs = slots.map { slot ->
        val y = nutY - rowNearest - spacing * slot.row
        val half = partial.halfWidthAt(y)
        val sign = if (slot.side == PegSide.LEFT) -1f else 1f
        val labelX = (centerX + sign * (half + knobOut + gap + labelSize / 2f))
            .coerceIn(labelSize / 2f, w - labelSize / 2f)
        PegGeometry(
            stringIndex = slot.stringIndex,
            side = slot.side,
            row = slot.row,
            y = y,
            postX = centerX + sign * (half - postInset),
            edgeX = centerX + sign * half,
            knobX = centerX + sign * (half + knobOut * 0.68f),
            labelX = labelX
        )
    }

    return partial.copy(nutStringX = nutStringX, pegs = pegs)
}
