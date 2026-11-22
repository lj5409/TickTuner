package com.gtool.feature.metronome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BpmNumpadLogicTest {

    @Test
    fun initialStateUsesCurrentBpmWithReplaceFlag() {
        val state = BpmNumpadLogic.initial(120)
        assertEquals("120", state.text)
        assertTrue(state.replaceOnFirstDigit)
        assertEquals(120, BpmNumpadLogic.parsedBpm(state))
    }

    @Test
    fun firstDigitOverwritesInitialText() {
        val state = BpmNumpadLogic.initial(120)
        val next = BpmNumpadLogic.inputDigit(state, '9')
        assertEquals("9", next.text)
        assertFalse(next.replaceOnFirstDigit)
    }

    @Test
    fun subsequentDigitsAppendUpToThreeDigits() {
        var state = BpmNumpadLogic.initial(120)
        state = BpmNumpadLogic.inputDigit(state, '1')
        state = BpmNumpadLogic.inputDigit(state, '4')
        state = BpmNumpadLogic.inputDigit(state, '5')
        assertEquals("145", state.text)
        assertEquals(145, BpmNumpadLogic.parsedBpm(state))
    }

    @Test
    fun maxThreeDigitCapIgnoresExtraDigits() {
        var state = BpmNumpadLogic.initial(90)
        state = BpmNumpadLogic.inputDigit(state, '2')
        state = BpmNumpadLogic.inputDigit(state, '4')
        state = BpmNumpadLogic.inputDigit(state, '0')
        state = BpmNumpadLogic.inputDigit(state, '8')
        assertEquals("240", state.text)
        assertEquals(240, BpmNumpadLogic.parsedBpm(state))
    }

    @Test
    fun nonDigitInputIsIgnored() {
        val initial = BpmNumpadLogic.initial(120)
        assertEquals(initial, BpmNumpadLogic.inputDigit(initial, 'a'))
        assertEquals(initial, BpmNumpadLogic.inputDigit(initial, '-'))
    }

    @Test
    fun leadingZeroIsReplacedBySubsequentDigit() {
        var state = BpmNumpadLogic.initial(120)
        state = BpmNumpadLogic.inputDigit(state, '0')
        assertEquals("0", state.text)
        state = BpmNumpadLogic.inputDigit(state, '5')
        assertEquals("5", state.text)
        state = BpmNumpadLogic.inputDigit(state, '8')
        assertEquals("58", state.text)
        assertEquals(58, BpmNumpadLogic.parsedBpm(state))
    }

    @Test
    fun backspaceRemovesLastCharAndDisablesOverwrite() {
        var state = BpmNumpadLogic.initial(128)
        state = BpmNumpadLogic.backspace(state)
        assertEquals("12", state.text)
        assertFalse(state.replaceOnFirstDigit)
        state = BpmNumpadLogic.inputDigit(state, '4')
        assertEquals("124", state.text)
    }

    @Test
    fun backspaceOnEmptyRemainsEmpty() {
        val empty = BpmNumpadLogic.clear()
        val after = BpmNumpadLogic.backspace(empty)
        assertEquals("", after.text)
        assertFalse(after.replaceOnFirstDigit)
    }

    @Test
    fun clearEmptiesTextAndDisablesOverwrite() {
        val cleared = BpmNumpadLogic.clear()
        assertEquals("", cleared.text)
        assertFalse(cleared.replaceOnFirstDigit)
        assertNull(BpmNumpadLogic.parsedBpm(cleared))
    }

    @Test
    fun parsedBpmValidatesRangeBounds() {
        assertNull(BpmNumpadLogic.parsedBpm(BpmNumpadLogic.State("")))
        assertNull(BpmNumpadLogic.parsedBpm(BpmNumpadLogic.State("0")))
        assertNull(BpmNumpadLogic.parsedBpm(BpmNumpadLogic.State("29")))
        assertEquals(30, BpmNumpadLogic.parsedBpm(BpmNumpadLogic.State("30")))
        assertEquals(120, BpmNumpadLogic.parsedBpm(BpmNumpadLogic.State("120")))
        assertEquals(300, BpmNumpadLogic.parsedBpm(BpmNumpadLogic.State("300")))
        assertNull(BpmNumpadLogic.parsedBpm(BpmNumpadLogic.State("301")))
    }
}
