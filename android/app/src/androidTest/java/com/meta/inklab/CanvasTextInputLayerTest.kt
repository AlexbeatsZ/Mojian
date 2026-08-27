package com.meta.inklab

import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanvasTextInputLayerTest {
    @Test
    fun blankTapCreatesUncommittedCaretAndNavigationDiscardsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var layer: CanvasTextInputLayer
        instrumentation.runOnMainSync {
            layer = CanvasTextInputLayer(ApplicationProvider.getApplicationContext(), ViewportState())
            layout(layer)
            layer.createTemporaryEditor(240f, 240f)

            assertEquals(1, layer.childCount)
            assertEquals(1, layer.selectedCount())
            assertTrue(layer.isEditing())
            assertTrue(layer.snapshotEditors().isEmpty())

            layer.abandonTemporaryEditors()
            assertEquals(0, layer.childCount)
            assertEquals(0, layer.selectedCount())
        }
    }

    @Test
    fun textUsesSelectThenEditWithoutDoubleTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var layer: CanvasTextInputLayer
        instrumentation.runOnMainSync {
            layer = CanvasTextInputLayer(ApplicationProvider.getApplicationContext(), ViewportState())
            layout(layer)
            layer.replaceEditors(
                listOf(CanvasTextSnapshot("已有文本", 220f, 220f, 300, 48, 1f, 0f, 1f)),
            )

            assertTrue(layer.selectAt(240f, 240f))
            assertEquals(1, layer.selectedCount())
            assertFalse(layer.isEditing())

            assertTrue(layer.editSelectedAt(240f, 240f, showIme = false))
            assertTrue(layer.isEditing())
        }
    }

    @Test
    fun firstValidCharacterCommitsTemporaryTextToSnapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var layer: CanvasTextInputLayer
        instrumentation.runOnMainSync {
            layer = CanvasTextInputLayer(ApplicationProvider.getApplicationContext(), ViewportState())
            layout(layer)
            layer.createTemporaryEditor(240f, 240f)
            val editor = layer.getChildAt(0) as android.widget.EditText
            editor.setText("墨")

            assertEquals(1, layer.snapshotEditors().size)
            layer.endEditing()
            assertEquals(1, layer.childCount)
        }
    }

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1200, 800)
    }
}
