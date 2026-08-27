package com.meta.inklab

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectInputPolicyTest {
    @Test
    fun penToolDoesNotParticipateInFingerOrMouseDecision() {
        assertEquals(
            DirectPointerDownIntent.MARQUEE_CANDIDATE,
            DirectInputPolicy.pointerDownIntent(1, true, false, false, false),
        )
        assertEquals(
            DirectPointerDownIntent.TRANSFORM_SELECTION,
            DirectInputPolicy.pointerDownIntent(1, true, false, true, false),
        )
    }

    @Test
    fun secondFingerAlwaysPromotesSequenceToPageNavigation() {
        assertEquals(
            DirectPointerDownIntent.PAGE_NAVIGATION,
            DirectInputPolicy.pointerDownIntent(2, true, true, true, true),
        )
    }

    @Test
    fun activeTextEditingKeepsNativeCaretAndSelectionGestures() {
        assertEquals(
            DirectPointerDownIntent.NATIVE_TEXT_EDIT,
            DirectInputPolicy.pointerDownIntent(1, true, true, true, false),
        )
    }

    @Test
    fun insertSpaceUsesFingerOrPenDragAndPenOrMouseHover() {
        assertEquals(true, DirectInputPolicy.canDragInsertSpace(DirectInputDevice.FINGER))
        assertEquals(false, DirectInputPolicy.canDragInsertSpace(DirectInputDevice.MOUSE))
        assertEquals(true, DirectInputPolicy.canDragInsertSpace(DirectInputDevice.PEN))
        assertEquals(false, DirectInputPolicy.showsInsertSpaceHover(DirectInputDevice.FINGER))
        assertEquals(true, DirectInputPolicy.showsInsertSpaceHover(DirectInputDevice.MOUSE))
        assertEquals(true, DirectInputPolicy.showsInsertSpaceHover(DirectInputDevice.PEN))
    }
}
