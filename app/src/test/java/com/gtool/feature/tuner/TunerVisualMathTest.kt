package com.gtool.feature.tuner

import com.gtool.core.data.AppSettings
import com.gtool.core.music.Note
import com.gtool.core.music.PitchClass
import com.gtool.core.music.TuningLibrary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

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

    @Test
    fun evaluateTunerPitch_freeMode_targetsNearestChromaticNoteAndClearsStringMatch() {
        // C4（261.63 Hz）不属于标准吉他调弦 EADGBE：自由模式下应以最近音名 C4 为目标且音分接近 0
        val c4 = Note(PitchClass.C, 4)
        val c4Hz = c4.frequency(440.0)
        val freeMatch = evaluateTunerPitch(
            frequency = c4Hz,
            tuning = TuningLibrary.STANDARD,
            a4 = 440.0,
            freeMode = true,
            lockedIndex = 0
        )
        assertEquals(c4, freeMatch.detectedNote)
        assertEquals(c4, freeMatch.targetNote)
        assertNull("free mode must not match a guitar string", freeMatch.matchedIndex)
        assertNull("free mode must ignore locked string index", freeMatch.targetIndex)
        assertEquals(0.0, freeMatch.cents!!, 1e-6)
        assertTrue(freeMatch.inTune)

        // 偏高 +12 音分：目标仍为 C4，音分约 +12，未准
        val sharpHz = c4Hz * 2.0.pow(12.0 / 1200.0)
        val sharpMatch = evaluateTunerPitch(
            frequency = sharpHz,
            tuning = TuningLibrary.STANDARD,
            a4 = 440.0,
            freeMode = true,
            lockedIndex = null
        )
        assertEquals(c4, sharpMatch.targetNote)
        assertEquals(12.0, sharpMatch.cents!!, 1e-4)
        assertFalse(sharpMatch.inTune)
    }

    @Test
    fun evaluateTunerPitch_freeMode_respectsBoundaryAndCustomA4() {
        // 跨越半音中点（+49¢ -> A4，+51¢ -> A#4 且音分约 -49¢），且所有音分始终在 [-50, +50] 内
        val a4 = Note(PitchClass.A, 4)
        val aSharp4 = Note(PitchClass.A_SHARP, 4)
        val belowMid = evaluateTunerPitch(
            frequency = a4.frequency(440.0) * 2.0.pow(49.0 / 1200.0),
            tuning = TuningLibrary.STANDARD,
            a4 = 440.0,
            freeMode = true,
            lockedIndex = null
        )
        assertEquals(a4, belowMid.targetNote)
        assertEquals(49.0, belowMid.cents!!, 1e-4)

        val aboveMid = evaluateTunerPitch(
            frequency = a4.frequency(440.0) * 2.0.pow(51.0 / 1200.0),
            tuning = TuningLibrary.STANDARD,
            a4 = 440.0,
            freeMode = true,
            lockedIndex = null
        )
        assertEquals(aSharp4, aboveMid.targetNote)
        assertEquals(-49.0, aboveMid.cents!!, 1e-4)

        // 跟随自定义 A4 = 442 Hz
        val a442Match = evaluateTunerPitch(
            frequency = 442.0,
            tuning = TuningLibrary.STANDARD,
            a4 = 442.0,
            freeMode = true,
            lockedIndex = null
        )
        assertEquals(a4, a442Match.targetNote)
        assertEquals(0.0, a442Match.cents!!, 1e-6)
        assertTrue(a442Match.inTune)
    }

    @Test
    fun evaluateTunerPitch_guitarMode_matchesNearestStringAndPreservesOctaveOffset() {
        // 标准调弦下弹 C4：最近吉他弦为 2 弦 B3，相对 B3 约 +100 音分
        val c4Hz = Note(PitchClass.C, 4).frequency(440.0)
        val autoMatch = evaluateTunerPitch(
            frequency = c4Hz,
            tuning = TuningLibrary.STANDARD,
            a4 = 440.0,
            freeMode = false,
            lockedIndex = null
        )
        assertEquals(4, autoMatch.matchedIndex)
        assertEquals(4, autoMatch.targetIndex)
        assertEquals(Note(PitchClass.B, 3), autoMatch.targetNote)
        assertEquals(100.0, autoMatch.cents!!, 1e-4)
        assertFalse(autoMatch.inTune)

        // 锁定 6 弦 E2 时弹高八度 E3：严禁折叠八度，保留 +1200 音分
        val e3Hz = Note(PitchClass.E, 3).frequency(440.0)
        val lockedMatch = evaluateTunerPitch(
            frequency = e3Hz,
            tuning = TuningLibrary.STANDARD,
            a4 = 440.0,
            freeMode = false,
            lockedIndex = 0
        )
        assertEquals(0, lockedMatch.targetIndex)
        assertEquals(Note(PitchClass.E, 2), lockedMatch.targetNote)
        assertEquals(1200.0, lockedMatch.cents!!, 1e-4)
        assertFalse(lockedMatch.inTune)
    }

    @Test
    fun appSettings_tunerFreeModeDefaultsFalseForLegacyJsonAndRoundTrips() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = json.decodeFromString(
            AppSettings.serializer(),
            """{"a4":440.0,"useFlats":true,"lastTuningId":"drop_d"}"""
        )
        assertFalse(legacy.tunerFreeMode)
        assertTrue(legacy.useFlats)
        assertEquals("drop_d", legacy.lastTuningId)

        val encoded = json.encodeToString(AppSettings.serializer(), AppSettings(tunerFreeMode = true))
        val decoded = json.decodeFromString(AppSettings.serializer(), encoded)
        assertTrue(decoded.tunerFreeMode)
    }
}
