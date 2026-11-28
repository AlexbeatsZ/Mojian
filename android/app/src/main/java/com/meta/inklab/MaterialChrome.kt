package com.meta.inklab

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/** Material state layers stay on chrome. They never enter the ink/render pipeline. */
internal object MaterialChrome {
    fun background(context: Context, selected: Boolean = false, filled: Boolean = false, radius: Int = 24): RippleDrawable {
        val density = context.resources.displayMetrics.density
        fun shape(color: Int, focus: Boolean = false) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * density
            if (focus) setStroke((3 * density).roundToInt(), MaterialTokens.PRIMARY)
        }
        val fill = when {
            selected -> MaterialTokens.SECONDARY_CONTAINER
            filled -> MaterialTokens.SURFACE_CONTAINER_HIGH
            else -> Color.TRANSPARENT
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(fill, true))
            addState(intArrayOf(android.R.attr.state_hovered), shape(if (selected) fill else MaterialTokens.SURFACE_CONTAINER_HIGHEST))
            addState(intArrayOf(), shape(fill))
        }
        return RippleDrawable(ColorStateList.valueOf(0x1FE6E0E9), states, shape(Color.WHITE))
    }

    fun interactive(view: View, selected: Boolean = false) {
        view.isFocusable = true
        view.defaultFocusHighlightEnabled = false
        view.isSelected = selected
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
    }
}

/** Expanded: three panes. Medium: page list + canvas. Compact: one overlay drawer.
 * The logical pane preferences survive a resize; no notebook/page state is changed.
 */
internal class MaterialWorkspaceLayout(
    context: Context,
    private val rail: View,
    private val sections: View,
    private val pages: View,
    private val canvas: View,
) : FrameLayout(context) {
    private var showRail = true
    private var showSections = true
    private var showPages = true
    var preferPages = true
    private var drawerOpen = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    init {
        addView(canvas)
        addView(rail)
        addView(sections)
        addView(pages)
        setBackgroundColor(MaterialTokens.SURFACE)
    }

    fun configure(railVisible: Boolean, sectionsVisible: Boolean, pagesVisible: Boolean) {
        showRail = railVisible
        showSections = sectionsVisible
        showPages = pagesVisible
        requestLayout()
    }

    fun toggleDrawer(pages: Boolean): Boolean {
        if (width >= dp(840)) return false
        drawerOpen = !drawerOpen || preferPages != pages
        preferPages = pages
        requestLayout()
        return true
    }

    fun dismissDrawer() {
        drawerOpen = false
        requestLayout()
    }

    fun showPageDrawer() {
        if (width >= dp(840)) return
        preferPages = true
        drawerOpen = true
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val expanded = width >= dp(840)
        val compact = width < dp(600)
        val railWidth = if (showRail && !compact) dp(80) else 0
        val sectionWidth = min(dp(248), width - railWidth - dp(32))
        val pageWidth = min(dp(240), width - railWidth - dp(32))
        val sectionVisible = if (expanded) showSections else drawerOpen && !preferPages
        val pageVisible = if (expanded) showPages else drawerOpen && preferPages
        val sectionInline = if (expanded && sectionVisible) sectionWidth else 0
        val pageInline = if (expanded && pageVisible) pageWidth else 0
        fun measure(view: View, w: Int, visible: Boolean) {
            view.visibility = if (visible) VISIBLE else GONE
            if (visible) view.measure(MeasureSpec.makeMeasureSpec(w.coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
        measure(rail, railWidth, railWidth > 0)
        measure(sections, sectionWidth, sectionVisible)
        measure(pages, pageWidth, pageVisible)
        measure(canvas, width - railWidth - sectionInline - pageInline, true)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val expanded = width >= dp(840)
        val compact = width < dp(600)
        val railWidth = if (rail.visibility == VISIBLE) rail.measuredWidth else 0
        val sectionInline = if (expanded && sections.visibility == VISIBLE) sections.measuredWidth else 0
        val pageInline = if (expanded && pages.visibility == VISIBLE) pages.measuredWidth else 0
        canvas.layout(railWidth + sectionInline + pageInline, 0, width, height)
        rail.layout(0, 0, railWidth, height)
        if (sections.visibility == VISIBLE) sections.layout(railWidth, 0, railWidth + sections.measuredWidth, height)
        if (pages.visibility == VISIBLE) pages.layout(railWidth + sectionInline, 0, railWidth + sectionInline + pages.measuredWidth, height)
    }
}
