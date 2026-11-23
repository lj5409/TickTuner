package com.gtool.feature.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TunerVisualMathTest {

    @Test
    fun centsToFraction_mapsAndClamps() {
        assertEquals(0.5f, TrailMath.centsToFraction(0f), 1e-6f)
        assertEquals(0f, TrailMath.centsToFraction(-50f), 1e-6f)
        assertEquals(1f, TrailMath.centsToFraction(50f), 1e-6f)
        assertEquals(0.75f, TrailMath.centsToFraction(25f), 1e-6f)
        // 差八度等超量程值钳在两端
        assertEquals(1f, TrailMath.centsToFraction(1200f), 1e-6f)
        assertEquals(0f, TrailMath.centsToFraction(-700f), 1e-6f)
        assertEquals(0.5f, TrailMath.centsToFraction(Float.NaN), 1e-6f)
    }

    @Test
    fun ageAlpha_fadesMonotonically() {
        assertEquals(1f, TrailMath.ageAlpha(0L, 3000L), 1e-6f)
        assertEquals(0f, TrailMath.ageAlpha(3000L, 3000L), 1e-6f)
        assertEquals(0f, TrailMath.ageAlpha(5000L, 3000L), 1e-6f)
        var previous = 1f
        for (age in 100L..2900L step 100L) {
            val alpha = TrailMath.ageAlpha(age, 3000L)
            assertTrue("alpha must decrease", alpha < previous)
            previous = alpha
        }
        assertEquals(0f, TrailMath.ageFraction(0L), 1e-6f)
        assertEquals(1f, TrailMath.ageFraction(99_999L), 1e-6f)
    }

    @Test
    fun zoneOf_usesInTuneThreshold() {
        assertEquals(CentsZone.IN_TUNE, TrailMath.zoneOf(0f))
        assertEquals(CentsZone.IN_TUNE, TrailMath.zoneOf(3f))
        assertEquals(CentsZone.IN_TUNE, TrailMath.zoneOf(-3f))
        assertEquals(CentsZone.SHARP, TrailMath.zoneOf(3.5f))
        assertEquals(CentsZone.FLAT, TrailMath.zoneOf(-12f))
    }

    @Test
    fun trail_throttlesAndKeepsSingleGapMarker() {
        val trail = CentsTrail(capacity = 16)
        assertFalse("leading gap is ignored", trail.push(0L, null))
        assertTrue(trail.push(0L, 1f))
        assertFalse("too soon", trail.push(5L, 2f))
        assertTrue(trail.push(20L, 2f))
        assertTrue("first gap marker recorded", trail.push(25L, null))
        assertFalse("consecutive gap collapsed", trail.push(60L, null))
        assertTrue(trail.push(61L, 4f))
        assertEquals(4, trail.size)
        assertEquals(1f, trail.centsAt(0), 0f)
        assertTrue(trail.centsAt(2).isNaN())
        assertEquals(61L, trail.timeAt(3))
    }

    @Test
    fun trail_ringOverwritesOldest() {
        val trail = CentsTrail(capacity = 4)
        for (i in 0 until 6) trail.push(i * 20L, i.toFloat())
        assertEquals(4, trail.size)
        assertEquals(2f, trail.centsAt(0), 0f)
        assertEquals(5f, trail.centsAt(3), 0f)
    }

    @Test
    fun trail_pruneDrainsToEmpty() {
        val trail = CentsTrail()
        trail.push(0L, 1f)
        trail.push(20L, 2f)
        trail.push(40L, null)
        trail.prune(nowMs = 3010L, windowMs = 3000L)
        assertEquals(2, trail.size)
        trail.prune(nowMs = 3020L, windowMs = 3000L)
        assertEquals("only gap marker left -> cleared", 0, trail.size)
    }

    @Test
    fun pegSlots_standardSixString() {
        val slots = headstockPegSlots(6).associateBy { it.stringIndex }
        // 6 弦（下标 0）在左下，1 弦（下标 5）在右下，4/3 弦在最上
        assertEquals(PegSlot(0, PegSide.LEFT, 0), slots[0])
        assertEquals(PegSlot(1, PegSide.LEFT, 1), slots[1])
        assertEquals(PegSlot(2, PegSide.LEFT, 2), slots[2])
        assertEquals(PegSlot(3, PegSide.RIGHT, 2), slots[3])
        assertEquals(PegSlot(4, PegSide.RIGHT, 1), slots[4])
        assertEquals(PegSlot(5, PegSide.RIGHT, 0), slots[5])
    }

    @Test
    fun pegSlots_oddAndEmpty() {
        assertTrue(headstockPegSlots(0).isEmpty())
        val seven = headstockPegSlots(7)
        assertEquals(4, seven.count { it.side == PegSide.LEFT })
        assertEquals(3, seven.count { it.side == PegSide.RIGHT })
    }

    @Test
    fun geometry_labelsStayInsideAndDoNotOverlap() {
        val sizes = listOf(
            320f to 300f,   // 竖屏手机
            380f to 330f,   // 横屏右栏
            360f to 700f,   // 展开右栏（高瘦）
            700f to 260f    // 悬停下半屏（矮宽）
        )
        for ((w, h) in sizes) {
            val geo = computeHeadstockGeometry(w, h, 6)
            assertEquals(6, geo.pegs.size)
            assertEquals(6, geo.nutStringX.size)
            assertTrue(geo.neckBottom <= h + 0.5f)
            assertTrue(geo.labelSize >= 40f)
            for (peg in geo.pegs) {
                assertTrue("label inside ($w x $h)", peg.labelX - geo.labelSize / 2f >= -0.01f)
                assertTrue("label inside ($w x $h)", peg.labelX + geo.labelSize / 2f <= w + 0.01f)
                assertTrue(peg.y > geo.bodyTop && peg.y < geo.nutY)
                // 弦轴在琴头内，旋钮在琴头外
                assertTrue(abs(peg.postX - geo.centerX) < geo.halfWidthAt(peg.y))
                assertTrue(abs(peg.knobX - geo.centerX) > geo.halfWidthAt(peg.y))
            }
            for (side in PegSide.entries) {
                val ys = geo.pegs.filter { it.side == side }.sortedBy { it.row }.map { it.y }
                ys.zipWithNext { lower, upper ->
                    assertTrue("row 0 nearest nut", lower > upper)
                    assertTrue("labels must not overlap ($w x $h)", lower - upper >= geo.labelSize * 0.9f)
                }
            }
        }
    }

    @Test
    fun geometry_withTopInset_shiftsHeadstockBelowTuningBadge() {
        val normal = computeHeadstockGeometry(380f, 330f, 6)
        val insetGeo = computeHeadstockGeometry(380f, 330f, 6, topInset = HEADSTOCK_BADGE_TOP_INSET_DP)
        assertTrue("headstock shifted down below badge", insetGeo.bodyTop >= HEADSTOCK_BADGE_TOP_INSET_DP)
        assertTrue("headstock bodyTop lower than normal", insetGeo.bodyTop > normal.bodyTop + 20f)
        assertTrue("neck still fits in container", insetGeo.neckBottom <= 330f + 0.5f)
        for (peg in insetGeo.pegs) {
            assertTrue(peg.y > insetGeo.bodyTop && peg.y < insetGeo.nutY)
        }
        // 琴头肩部显著宽于琴枕，弦钮区 3 个弦轴近乎竖直排列（非倒三角斜列）
        assertTrue(insetGeo.shoulderWidth >= insetGeo.topWidth * 0.88f)
        val leftPosts = insetGeo.pegs.filter { it.side == PegSide.LEFT }.map { it.postX }
        assertTrue(leftPosts.max() - leftPosts.min() <= insetGeo.topWidth * 0.06f)
    }
}
