package com.gtool.core.music

import org.junit.Assert.assertEquals
import org.junit.Test

class TuningOrderTest {

    private val customA = TuningLibrary.STANDARD.copyAsCustom("自定义 A", "custom_a")
    private val customB = TuningLibrary.DADGAD.copyAsCustom("自定义 B", "custom_b")

    @Test
    fun orderedAll_emptyOrder_returnsDefaultBuiltInsThenCustoms() {
        val result = TuningLibrary.orderedAll(listOf(customA, customB), emptyList())
        assertEquals(TuningLibrary.builtIns + listOf(customA, customB), result)
    }

    @Test
    fun orderedAll_mixedBuiltInAndCustomOrder_respectsRequestedOrder() {
        val order = listOf(
            customB.id,
            TuningLibrary.DADGAD.id,
            TuningLibrary.DROP_D.id,
            customA.id,
            TuningLibrary.STANDARD.id
        )
        val result = TuningLibrary.orderedAll(listOf(customA, customB), order)
        assertEquals(
            listOf(customB.id, TuningLibrary.DADGAD.id, TuningLibrary.DROP_D.id, customA.id, TuningLibrary.STANDARD.id),
            result.take(5).map { it.id }
        )
        assertEquals(TuningLibrary.builtIns.size + 2, result.size)
    }

    @Test
    fun orderedAll_ignoresStaleAndDuplicateIds_andAppendsMissing() {
        val order = listOf("deleted_id", customA.id, customA.id, TuningLibrary.OPEN_G.id)
        val result = TuningLibrary.orderedAll(listOf(customA, customB), order)
        assertEquals(customA.id, result[0].id)
        assertEquals(TuningLibrary.OPEN_G.id, result[1].id)
        assertEquals(TuningLibrary.STANDARD.id, result[2].id)
        assertEquals(customB.id, result.last().id)
        assertEquals(TuningLibrary.builtIns.size + 2, result.size)
    }

    @Test
    fun moveItem_movesForwardAndBackwardCorrectly() {
        val base = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), TuningLibrary.moveItem(base, 0, 2))
        assertEquals(listOf("a", "d", "b", "c"), TuningLibrary.moveItem(base, 3, 1))
        assertEquals(base, TuningLibrary.moveItem(base, 1, 1))
        assertEquals(base, TuningLibrary.moveItem(base, -1, 2))
        assertEquals(base, TuningLibrary.moveItem(base, 0, 99))
    }

    @Test
    fun compactPitchNames_formatsWithoutOctaveAndRespectsFlats() {
        assertEquals("EADGBE", TuningLibrary.STANDARD.compactPitchNames())
        assertEquals("DADGBE", TuningLibrary.DROP_D.compactPitchNames())
        assertEquals("DADGAD", TuningLibrary.DADGAD.compactPitchNames())
        assertEquals("D#G#C#F#A#D#", TuningLibrary.HALF_STEP_DOWN.compactPitchNames(useFlats = false))
        assertEquals("EbAbDbGbBbEb", TuningLibrary.HALF_STEP_DOWN.compactPitchNames(useFlats = true))
    }
}
