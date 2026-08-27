package com.meta.inklab

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

/** App-owned command surface shared by ribbon drop-downs and context menus. */
class MojianCommandMenu private constructor(
    private val context: Context,
    private val items: List<Item>,
    private val width: Int,
    private val pointerButtonHandler: ((MotionEvent) -> Boolean)?,
) {
    data class Item(
        val label: String,
        val icon: String = "document_page",
        val enabled: Boolean = true,
        val checked: Boolean = false,
        val dividerBefore: Boolean = false,
        val destructive: Boolean = false,
        val trailingText: String? = null,
        val submenu: List<Item> = emptyList(),
        val action: (() -> Unit)? = null,
    )

    private var rootPopup: PopupWindow? = null
    private var childPopup: PopupWindow? = null

    fun show(anchor: View) {
        dismiss()
        val popup = createPopup(items, isRoot = true)
        rootPopup = popup
        activeMenu = this
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val metrics = context.resources.displayMetrics
        val expectedHeight = menuHeight(items)
        val x = location[0].coerceIn(dp(8), (metrics.widthPixels - width - dp(8)).coerceAtLeast(dp(8)))
        val below = location[1] + anchor.height + dp(2)
        val y = if (below + expectedHeight <= metrics.heightPixels - dp(8)) below else (location[1] - expectedHeight - dp(2)).coerceAtLeast(dp(8))
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    fun showAt(anchor: View, screenX: Int, screenY: Int) {
        dismiss()
        val popup = createPopup(items, isRoot = true)
        rootPopup = popup
        activeMenu = this
        val metrics = context.resources.displayMetrics
        val expectedHeight = menuHeight(items)
        val x = screenX.coerceIn(dp(8), (metrics.widthPixels - width - dp(8)).coerceAtLeast(dp(8)))
        val y = screenY.coerceIn(dp(8), (metrics.heightPixels - expectedHeight - dp(8)).coerceAtLeast(dp(8)))
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    fun dismiss() {
        childPopup?.dismiss()
        childPopup = null
        rootPopup?.dismiss()
        rootPopup = null
        if (activeMenu === this) activeMenu = null
    }

    private fun createPopup(menuItems: List<Item>, isRoot: Boolean): PopupWindow {
        val content = buildContent(menuItems)
        return PopupWindow(content, width, menuHeight(menuItems), true).apply {
            isOutsideTouchable = true
            elevation = dp(12).toFloat()
            setBackgroundDrawable(rounded(MENU_BACKGROUND, dp(5).toFloat(), MENU_BORDER))
            if (isRoot) {
                setOnDismissListener {
                    childPopup?.dismiss()
                    childPopup = null
                    rootPopup = null
                    if (activeMenu === this@MojianCommandMenu) activeMenu = null
                }
            }
        }
    }

    private fun buildContent(menuItems: List<Item>): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(6), dp(5), dp(6))
            background = rounded(MENU_BACKGROUND, dp(5).toFloat(), MENU_BORDER)
            setOnGenericMotionListener { _, event -> forwardPointerButton(event) }
            setOnTouchListener { _, event -> forwardPointerButton(event) }
            menuItems.forEach { item ->
                if (item.dividerBefore) {
                    addView(
                        View(context).apply { setBackgroundColor(DIVIDER) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                            setMargins(dp(36), dp(3), dp(4), dp(3))
                        },
                    )
                }
                addView(commandRow(item), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
            }
        }

    private fun forwardPointerButton(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val buttonMask = MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY
        val isButtonEvent =
            when (event.actionMasked) {
                MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE -> event.actionButton and buttonMask != 0
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> event.buttonState and buttonMask != 0
                else -> false
            }
        return isButtonEvent && pointerButtonHandler?.invoke(event) == true
    }

    private fun commandRow(item: Item): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            isEnabled = item.enabled
            alpha = if (item.enabled) 1f else 0.38f
            background = selectableBackground()
            setOnGenericMotionListener { _, event -> forwardPointerButton(event) }
            setOnTouchListener { _, event -> forwardPointerButton(event) }

            addView(
                ImageView(context).apply {
                    setImageDrawable(icon(item.icon))
                    imageTintList = ColorStateList.valueOf(if (item.destructive) DESTRUCTIVE else ICON_COLOR)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(11) },
            )
            addView(
                TextView(context).apply {
                    text = item.label
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(if (item.checked) ACCENT else TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            if (item.trailingText != null || item.submenu.isNotEmpty()) {
                addView(
                    TextView(context).apply {
                        text = item.trailingText ?: "›"
                        gravity = Gravity.CENTER
                        setTextColor(TEXT_SECONDARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item.submenu.isEmpty()) 10f else 20f)
                    },
                    LinearLayout.LayoutParams(if (item.submenu.isEmpty()) dp(82) else dp(24), ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }
            setOnClickListener {
                if (!item.enabled) return@setOnClickListener
                if (item.submenu.isNotEmpty()) {
                    showSubmenu(this, item.submenu)
                } else {
                    dismiss()
                    item.action?.invoke()
                }
            }
        }

    private fun showSubmenu(anchor: View, submenu: List<Item>) {
        childPopup?.dismiss()
        val popup = createPopup(submenu, isRoot = false)
        childPopup = popup
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val metrics = context.resources.displayMetrics
        val expectedHeight = menuHeight(submenu)
        val openRight = location[0] + anchor.width + width <= metrics.widthPixels - dp(8)
        val x = if (openRight) location[0] + anchor.width - dp(3) else location[0] - width + dp(3)
        val y = location[1].coerceIn(dp(8), (metrics.heightPixels - expectedHeight - dp(8)).coerceAtLeast(dp(8)))
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
    }

    private fun icon(name: String) =
        ContextCompat.getDrawable(
            context,
            when (name) {
                "add" -> R.drawable.ic_fluent_add_24_regular
                "arrow_redo" -> R.drawable.ic_fluent_arrow_redo_24_regular
                "arrow_undo" -> R.drawable.ic_fluent_arrow_undo_24_regular
                "clipboard_paste" -> R.drawable.ic_fluent_clipboard_paste_24_regular
                "copy" -> R.drawable.ic_fluent_copy_24_regular
                "crop" -> R.drawable.ic_fluent_crop_24_regular
                "cut" -> R.drawable.ic_fluent_cut_24_regular
                "delete_dismiss" -> R.drawable.ic_fluent_delete_dismiss_24_regular
                "document_pdf" -> R.drawable.ic_fluent_document_pdf_24_regular
                "eraser" -> R.drawable.ic_fluent_eraser_24_regular
                "eye_off" -> R.drawable.ic_fluent_eye_off_24_regular
                "full_screen_maximize" -> R.drawable.ic_fluent_full_screen_maximize_24_regular
                "full_screen_minimize" -> R.drawable.ic_fluent_full_screen_minimize_24_regular
                "hand_left" -> R.drawable.ic_fluent_hand_left_24_regular
                "highlight" -> R.drawable.ic_fluent_highlight_24_regular
                "image" -> R.drawable.ic_fluent_image_24_regular
                "lasso" -> R.drawable.ic_fluent_lasso_24_regular
                "link" -> R.drawable.ic_fluent_link_24_regular
                "navigation" -> R.drawable.ic_fluent_navigation_24_regular
                "notebook" -> R.drawable.ic_fluent_notebook_24_regular
                "pen" -> R.drawable.ic_fluent_pen_24_regular
                "rule_lines" -> R.drawable.ic_fluent_rule_lines_24_regular
                "split_view" -> R.drawable.ic_fluent_split_view_24_regular
                "style_guide" -> R.drawable.ic_fluent_style_guide_24_regular
                "tag" -> R.drawable.ic_fluent_tag_24_regular
                "tag_search" -> R.drawable.ic_fluent_tag_search_24_regular
                "zoom_in" -> R.drawable.ic_fluent_zoom_in_24_regular
                else -> R.drawable.ic_fluent_document_page_24_regular
            },
        )

    private fun selectableBackground(): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(PRESSED_BACKGROUND, dp(3).toFloat(), Color.TRANSPARENT))
            addState(intArrayOf(), rounded(Color.TRANSPARENT, dp(3).toFloat(), Color.TRANSPARENT))
        }

    private fun rounded(fill: Int, radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            setStroke(dp(1), stroke)
        }

    private fun menuHeight(menuItems: List<Item>): Int =
        dp(12 + menuItems.size * 40 + menuItems.count(Item::dividerBefore) * 7)

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    companion object {
        private val MENU_BACKGROUND = Color.rgb(39, 39, 39)
        private val MENU_BORDER = Color.rgb(112, 112, 112)
        private val PRESSED_BACKGROUND = Color.rgb(61, 61, 61)
        private val DIVIDER = Color.rgb(79, 79, 79)
        private val TEXT_PRIMARY = Color.rgb(242, 242, 242)
        private val TEXT_SECONDARY = Color.rgb(174, 174, 174)
        private val ICON_COLOR = Color.rgb(214, 214, 214)
        private val ACCENT = Color.rgb(218, 178, 233)
        private val DESTRUCTIVE = Color.rgb(238, 92, 92)

        private var activeMenu: MojianCommandMenu? = null

        fun dismissActive(): Boolean {
            val menu = activeMenu ?: return false
            menu.dismiss()
            return true
        }

        fun show(
            context: Context,
            anchor: View,
            items: List<Item>,
            widthDp: Int = 232,
            pointerButtonHandler: ((MotionEvent) -> Boolean)? = null,
        ): MojianCommandMenu {
            dismissActive()
            return MojianCommandMenu(
                context,
                items,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthDp.toFloat(), context.resources.displayMetrics).toInt(),
                pointerButtonHandler,
            ).also { it.show(anchor) }
        }

        fun showAt(
            context: Context,
            anchor: View,
            screenX: Int,
            screenY: Int,
            items: List<Item>,
            widthDp: Int = 232,
            pointerButtonHandler: ((MotionEvent) -> Boolean)? = null,
        ): MojianCommandMenu {
            dismissActive()
            return MojianCommandMenu(
                context,
                items,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, widthDp.toFloat(), context.resources.displayMetrics).toInt(),
                pointerButtonHandler,
            ).also { it.showAt(anchor, screenX, screenY) }
        }
    }
}
