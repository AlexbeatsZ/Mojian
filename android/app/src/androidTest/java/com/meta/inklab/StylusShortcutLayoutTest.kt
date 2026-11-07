package com.meta.inklab

import android.view.KeyEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusShortcutLayoutTest {
    @Test fun penKeyPairIsConsumedBeforeTheFocusedEditorAndOrdinaryKeysPassThrough() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val intercepted = mutableListOf<Int>()
            val childEvents = mutableListOf<Int>()
            val root = StylusShortcutLayout(context) { event ->
                // Device filtering lives in XiaomiStylusShortcutDispatcher; this view tests routing.
                (event.keyCode == KeyEvent.KEYCODE_PAGE_UP).also { handled ->
                    if (handled) intercepted += event.action
                }
            }
            val editor = object : EditText(context) {
                override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                    childEvents += keyCode
                    return true // Stand in for a focused editor/IME path that consumes keyboard keys.
                }
            }
            root.addView(editor, LinearLayout.LayoutParams(100, 50))
            root.layout(0, 0, 200, 100)
            editor.layout(0, 0, 100, 50)
            assertTrue(editor.requestFocus())
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                assertTrue(root.dispatchKeyEventPreIme(KeyEvent(action, KeyEvent.KEYCODE_PAGE_UP)))
            }
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), intercepted)
            assertTrue(childEvents.isEmpty())
            assertTrue(root.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
            assertEquals(listOf(KeyEvent.KEYCODE_A), childEvents)
        }
    }

    @Test fun canvasWithoutAnEditorRemainsInTheParentsPreImeFocusChain() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            var received = 0
            val parent = FrameLayout(context)
            val root = StylusShortcutLayout(context) { received++; true }
            parent.addView(root, FrameLayout.LayoutParams(200, 100))
            parent.layout(0, 0, 200, 100)
            root.layout(0, 0, 200, 100)
            assertTrue(root.requestFocus())
            assertTrue(parent.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP)))
            assertEquals(1, received)
        }
    }
}
