package com.gtool.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WindowPostureTest {

    @Test
    fun standardPhonePortrait_classifiesAsPortrait() {
        val posture = classifyPosture(
            widthDp = 390f,
            heightDp = 844f,
            isHalfOpened = false,
            isHorizontalFold = false
        )
        assertEquals(AppPosture.PORTRAIT, posture)
    }

    @Test
    fun standardPhoneLandscape_classifiesAsLandscapeCompact() {
        val posture = classifyPosture(
            widthDp = 844f,
            heightDp = 390f,
            isHalfOpened = false,
            isHorizontalFold = false
        )
        assertEquals(AppPosture.LANDSCAPE_COMPACT, posture)
    }

    @Test
    fun vivoXFold3ProOuterPortrait_classifiesAsPortrait() {
        val posture = classifyPosture(
            widthDp = 391f,
            heightDp = 916f,
            isHalfOpened = false,
            isHorizontalFold = false
        )
        assertEquals(AppPosture.PORTRAIT, posture)
    }

    @Test
    fun vivoXFold3ProOuterLandscapeOrTentMode_classifiesAsLandscapeCompact() {
        val posture = classifyPosture(
            widthDp = 916f,
            heightDp = 391f,
            isHalfOpened = false,
            isHorizontalFold = false
        )
        assertEquals(AppPosture.LANDSCAPE_COMPACT, posture)
    }

    @Test
    fun vivoXFold3ProInnerUnfoldedPortrait_classifiesAsUnfoldedExpanded() {
        val posture = classifyPosture(
            widthDp = 733f,
            heightDp = 826f,
            isHalfOpened = false,
            isHorizontalFold = false
        )
        assertEquals(AppPosture.UNFOLDED_EXPANDED, posture)
    }

    @Test
    fun vivoXFold3ProInnerUnfoldedRotated_classifiesAsUnfoldedExpanded() {
        val posture = classifyPosture(
            widthDp = 826f,
            heightDp = 733f,
            isHalfOpened = false,
            isHorizontalFold = false
        )
        assertEquals(AppPosture.UNFOLDED_EXPANDED, posture)
    }

    @Test
    fun halfOpenedHorizontalFold_classifiesAsTabletop() {
        assertEquals(
            AppPosture.TABLETOP,
            classifyPosture(
                widthDp = 826f,
                heightDp = 733f,
                isHalfOpened = true,
                isHorizontalFold = true
            )
        )
        assertEquals(
            AppPosture.TABLETOP,
            classifyPosture(
                widthDp = 733f,
                heightDp = 826f,
                isHalfOpened = true,
                isHorizontalFold = true
            )
        )
    }

    @Test
    fun halfOpenedVerticalFoldBookMode_classifiesAsUnfoldedExpanded() {
        assertEquals(
            AppPosture.UNFOLDED_EXPANDED,
            classifyPosture(
                widthDp = 733f,
                heightDp = 826f,
                isHalfOpened = true,
                isHorizontalFold = false
            )
        )
        assertEquals(
            AppPosture.UNFOLDED_EXPANDED,
            classifyPosture(
                widthDp = 826f,
                heightDp = 733f,
                isHalfOpened = true,
                isHorizontalFold = false
            )
        )
    }

    @Test
    fun thresholdBoundaries_matchSpecification() {
        assertEquals(
            AppPosture.UNFOLDED_EXPANDED,
            classifyPosture(widthDp = 600f, heightDp = 550f, isHalfOpened = false, isHorizontalFold = false)
        )
        assertEquals(
            AppPosture.LANDSCAPE_COMPACT,
            classifyPosture(widthDp = 600f, heightDp = 549f, isHalfOpened = false, isHorizontalFold = false)
        )
        assertEquals(
            AppPosture.LANDSCAPE_COMPACT,
            classifyPosture(widthDp = 599f, heightDp = 549f, isHalfOpened = false, isHorizontalFold = false)
        )
        assertEquals(
            AppPosture.PORTRAIT,
            classifyPosture(widthDp = 599f, heightDp = 600f, isHalfOpened = false, isHorizontalFold = false)
        )
    }

    @Test
    fun defaultWindowPostureState_andFoldHingeBounds_workOnJvm() {
        val defaultState = WindowPostureState()
        assertEquals(AppPosture.PORTRAIT, defaultState.posture)
        assertNull(defaultState.hingeBounds)
        assertFalse(defaultState.isVerticalBookFold)

        val hinge = FoldHingeBounds(
            leftPx = 0,
            topPx = 1090,
            rightPx = 2480,
            bottomPx = 1110,
            isHorizontal = true
        )
        assertEquals(0, hinge.leftPx)
        assertEquals(1090, hinge.topPx)
        assertEquals(2480, hinge.rightPx)
        assertEquals(1110, hinge.bottomPx)
        assertEquals(true, hinge.isHorizontal)
    }
}
