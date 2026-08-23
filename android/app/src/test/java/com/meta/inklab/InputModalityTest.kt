package com.meta.inklab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputModalityTest {
    private val finger = 1
    private val stylus = 2
    private val mouse = 3

    @Test
    fun textInputAcceptsFingerOnlyGesture() {
        assertTrue(InputModality.acceptsTextInput(listOf(finger), finger, mouse))
        assertTrue(InputModality.acceptsTextInput(listOf(finger, finger), finger, mouse))
        assertTrue(InputModality.isFingerOnly(listOf(finger), finger))
    }

    @Test
    fun textInputAcceptsExternalMouseWithoutTreatingItAsFinger() {
        assertTrue(InputModality.acceptsTextInput(listOf(mouse), finger, mouse))
        assertTrue(InputModality.isMouseOnly(listOf(mouse), mouse))
        assertFalse(InputModality.isFingerOnly(listOf(mouse), finger))
    }

    @Test
    fun textInputRejectsStylusAndMixedGestures() {
        assertFalse(InputModality.acceptsTextInput(listOf(stylus), finger, mouse))
        assertFalse(InputModality.acceptsTextInput(listOf(finger, stylus), finger, mouse))
        assertFalse(InputModality.acceptsTextInput(emptyList(), finger, mouse))
    }
}
