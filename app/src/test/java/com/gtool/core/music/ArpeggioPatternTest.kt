package com.gtool.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArpeggioPatternTest {

    @Test
    fun `sanitize keeps only string digits`() {
        assertEquals("53231323", ArpeggioPattern.sanitize("5a3 2-3 1x3 2 3"))
        assertEquals("165", ArpeggioPattern.sanitize("1 6 5 0 7 9"))
    }

    @Test
    fun `sanitize caps length`() {
        assertEquals(ArpeggioPattern.MAX_LENGTH, ArpeggioPattern.sanitize("1".repeat(40)).length)
    }

    @Test
    fun `normalize falls back to default when empty`() {
        assertEquals(ArpeggioPattern.DEFAULT, ArpeggioPattern.normalize(""))
        assertEquals(ArpeggioPattern.DEFAULT, ArpeggioPattern.normalize("abc"))
        assertEquals("5323", ArpeggioPattern.normalize("5323"))
    }

    @Test
    fun `string numbers map to voicing indices`() {
        // 6 弦 = 最低音弦 = 下标 0；1 弦 = 最高音弦 = 下标 5
        assertEquals(0, ArpeggioPattern.stringIndex('6'))
        assertEquals(5, ArpeggioPattern.stringIndex('1'))
        assertEquals(2, ArpeggioPattern.stringIndex('4'))
        assertEquals(-1, ArpeggioPattern.stringIndex('0'))
        assertEquals(-1, ArpeggioPattern.stringIndex('7'))
    }

    @Test
    fun `default pattern is the classic 53231323`() {
        val indices = ArpeggioPattern.indicesFor(ArpeggioPattern.DEFAULT)
        assertEquals(8, indices.size)
        assertEquals(listOf(1, 3, 4, 3, 5, 3, 4, 3), indices)
    }

    @Test
    fun `indices respect custom patterns`() {
        assertEquals(listOf(0, 5, 4), ArpeggioPattern.indicesFor("612"))
        assertTrue(ArpeggioPattern.indicesFor("").isNotEmpty())
    }
}

