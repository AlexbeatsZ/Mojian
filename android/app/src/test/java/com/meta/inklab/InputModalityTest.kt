package com.meta.inklab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputModalityTest {
    private val finger = 1
    private val stylus = 2

    @Test
    fun textInputAcceptsFingerOnlyGesture() {
        assertTrue(InputModality.acceptsTextInput(listOf(finger), finger))
        assertTrue(InputModality.acceptsTextInput(listOf(finger, finger), finger))
    }

    @Test
    fun textInputRejectsStylusAndMixedGestures() {
        assertFalse(InputModality.acceptsTextInput(listOf(stylus), finger))
        assertFalse(InputModality.acceptsTextInput(listOf(finger, stylus), finger))
        assertFalse(InputModality.acceptsTextInput(emptyList(), finger))
    }
}
