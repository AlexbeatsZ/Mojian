package com.meta.inklab

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutModelsTest {
    @Test
    fun catalogUsesConcreteTagActionsInsteadOfParentMenu() {
        val ids = ShortcutActionCatalog.actions(ToolShelf.defaults()).map { it.id }

        assertTrue("home:tag-important" in ids)
        assertTrue("home:tag-question" in ids)
        assertFalse("home:tag" in ids)
    }

    @Test
    fun assigningAnOccupiedChordMovesItToTheNewAction() {
        val settings = KeyboardShortcutSettings()
        val chord = ShortcutKeyChord(KeyEvent.KEYCODE_Z, ctrl = true)

        settings.assign("view:search", chord)

        assertEquals("view:search", settings.actionFor(chord))
        assertNull(settings.bindings["draw:undo"])
        assertEquals("Ctrl + Z", chord.displayName)
    }

    @Test
    fun defaultKeyboardChordsAreUnique() {
        val chords = KeyboardShortcutSettings.defaults().values

        assertEquals(chords.size, chords.distinct().size)
    }

    @Test
    fun xiaomiSettingsRoundTripAndClampTimings() {
        val settings =
            XiaomiPenShortcutSettings(
                pageUp = XiaomiButtonProfile(single = StylusActionBinding(StylusBindingType.TOGGLE_TOOL, "pen-blue")),
                doubleTapMillis = 30,
                holdMillis = 5_000,
            )

        val restored = XiaomiPenShortcutSettings.fromJson(settings.toJson()).sanitized(setOf("pen-blue"))

        assertEquals(StylusBindingType.TOGGLE_TOOL, restored.pageUp.single.type)
        assertEquals(150L, restored.doubleTapMillis)
        assertEquals(1_200L, restored.holdMillis)
    }
}
