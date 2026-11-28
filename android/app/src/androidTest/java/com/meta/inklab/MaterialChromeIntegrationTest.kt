package com.meta.inklab

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.loadingindicator.LoadingIndicator
import org.junit.Assert.*
import org.junit.Test

/** Runs only on an isolated emulator; never install instrumentation over the user's product. */
class MaterialChromeIntegrationTest {
    @Test fun adaptivePanesKeepTheCanvasUsableAndRestoreTheExpandedPreference() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val rail = View(context)
        val sections = View(context)
        val pages = View(context)
        val canvas = FrameLayout(context)
        val layout = MaterialWorkspaceLayout(context, rail, sections, pages, canvas)
        fun size(width: Int) {
            layout.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(600), View.MeasureSpec.EXACTLY))
            layout.layout(0, 0, dp(width), dp(600))
        }
        layout.configure(true, true, true)
        size(1280)
        assertEquals(View.VISIBLE, sections.visibility)
        assertEquals(View.VISIBLE, pages.visibility)
        assertTrue(canvas.width >= dp(700))
        size(360)
        assertEquals(View.GONE, rail.visibility)
        assertEquals(View.GONE, pages.visibility)
        assertEquals(dp(360), canvas.width)
        assertTrue(layout.toggleDrawer(false))
        size(360)
        assertEquals(View.VISIBLE, sections.visibility)
        assertEquals(View.GONE, pages.visibility)
        assertEquals(dp(360), canvas.width)
        layout.showPageDrawer()
        size(360)
        assertEquals(View.VISIBLE, pages.visibility)
        assertEquals(View.GONE, sections.visibility)
        layout.dismissDrawer()
        size(700)
        assertEquals(View.VISIBLE, rail.visibility)
        assertEquals(View.GONE, pages.visibility)
        assertTrue(canvas.width >= dp(600))
        size(1280)
        assertEquals(View.VISIBLE, sections.visibility)
        assertEquals(View.VISIBLE, pages.visibility)
    }

    @Test fun officialLoadingAndDialogComponentsAcceptTheProductTheme() = onMain {
        val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_Mojian_Expressive)
        val indicator = LoadingIndicator(context)
        indicator.setIndicatorColor(MaterialTokens.PRIMARY)
        indicator.setContainerColor(MaterialTokens.SURFACE_CONTAINER_HIGH)
        assertArrayEquals(intArrayOf(MaterialTokens.PRIMARY), indicator.indicatorColor)
        assertEquals(MaterialTokens.SURFACE_CONTAINER_HIGH, indicator.containerColor)
        // Construction catches missing AppCompat/Material theme attributes before release.
        val dialog = MaterialAlertDialogBuilder(context).setTitle("新建页面").setPositiveButton("创建", null).create()
        assertNotNull(dialog)
        val button = View(context)
        MaterialChrome.interactive(button, selected = true)
        assertTrue(button.isFocusable)
        assertTrue(button.isSelected)
        assertFalse(button.defaultFocusHighlightEnabled)
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
