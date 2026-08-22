package com.meta.inklab

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class RibbonPage {
    HOME,
    DRAW,
}

class MainActivity : Activity() {
    private val activeStrokes = mutableMapOf<Int, InProgressStrokeId>()
    private val viewport = ViewportState()
    private val pressureEraser = PressureEraserController()
    private val shortcutDispatcher = XiaomiStylusShortcutDispatcher()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val toolShelf = ToolShelf()
    private val notebookLibrary = NotebookLibrary.sample()
    private val fullPageToolbarConfig by lazy { FullPageToolbarConfig(loadFullPageToolbarIds()) }
    private val workspace: NotebookWorkspace
        get() = notebookLibrary.activeNotebook()

    private lateinit var wetInkView: InProgressStrokesView
    private lateinit var dryInkView: DryInkView
    private lateinit var predictor: MotionEventPredictor
    private lateinit var currentBrush: Brush
    private lateinit var toolShelfRow: LinearLayout
    private lateinit var floatingToolShelfRow: LinearLayout
    private lateinit var toolDetailRow: LinearLayout
    private var toolDetailPopup: PopupWindow? = null
    private var backstageDialog: Dialog? = null
    private lateinit var sectionList: LinearLayout
    private lateinit var pageList: LinearLayout
    private lateinit var addPageButton: TextView
    private lateinit var navigationPane: View
    private lateinit var pagePane: View
    private lateinit var rootLayout: LinearLayout
    private lateinit var ribbonTabs: View
    private lateinit var drawingRibbon: View
    private lateinit var navigationRail: View
    private lateinit var floatingToolbox: View
    private lateinit var textInputLayer: CanvasTextInputLayer
    private lateinit var tagLayer: CanvasTagLayer
    private lateinit var selectionOverlay: SelectionOverlayView
    private lateinit var sectionPaneToggle: View
    private lateinit var pagePaneToggle: View
    private lateinit var toolbarToggle: View
    private lateinit var fullPageToggle: View
    private lateinit var fullPageToggleIcon: ImageView

    private var predictionEnabled = true
    private var transformGestureActive = false
    private var lastGestureFocusX = 0f
    private var lastGestureFocusY = 0f
    private var lastGestureSpan = 0f
    private var pressurePointerId: Int? = null
    private var lastPressure = 0f
    private var lastPressurePoint = PointF()
    private var activeDirectTool: DrawingToolKind? = null
    private var lastPenToolId: String = toolShelf.tools.first { it.kind == DrawingToolKind.PEN }.id
    private var singleFingerPanEnabled = false
    private var textModeEnabled = false
    private var sectionPaneVisible = true
    private var pagePaneVisible = true
    private var drawingToolbarVisible = true
    private var fullPageMode = false
    private var activeRibbonPage = RibbonPage.DRAW
    private var restoreTextModeAfterTagPlacement = false

    private val activatePressureEraser =
        Runnable {
            val pointerId = pressurePointerId ?: return@Runnable
            if (!pressureEraser.activate(SystemClock.uptimeMillis())) return@Runnable
            activeStrokes.remove(pointerId)?.let(wetInkView::cancelStroke)
            eraseAt(lastPressurePoint, dp(18) / viewport.scale)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = TITLE_PURPLE
        window.navigationBarColor = NAV_BACKGROUND
        currentBrush = brushFor(toolShelf.selected)
        setContentView(buildInterface())
        pressureEraser.stationarySlop = dp(2).toFloat()
        syncPressureEraserWithTool()
        bindInkPipeline()
        renderWorkspace()
        renderToolShelf()
        renderToolDetails()
        wetInkView.post { wetInkView.eagerInit() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (shortcutDispatcher.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        toolDetailPopup?.dismiss()
        toolDetailPopup = null
        mainHandler.removeCallbacks(activatePressureEraser)
        pressureEraser.cancelCandidate()
        pressurePointerId = null
        activeDirectTool = null
        transformGestureActive = false
        dryInkView.setEraserCursor(null)
        clearContentSelection()
        textModeEnabled = false
        restoreTextModeAfterTagPlacement = false
        if (::textInputLayer.isInitialized) textInputLayer.setTextMode(false)
        if (::tagLayer.isInitialized) tagLayer.cancelPlacement()
        wetInkView.cancelUnfinishedStrokes()
        activeStrokes.clear()
        super.onPause()
    }

    private fun buildInterface(): View {
        rootLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(PAPER_COLOR)
            }
        rootLayout.setOnApplyWindowInsetsListener { view, insets ->
            val (top, bottom) =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    bars.top to bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
            view.setPadding(0, top, 0, bottom)
            insets
        }

        ribbonTabs = buildRibbonTabs()
        drawingRibbon =
            FrameLayout(this).apply {
                setBackgroundColor(TOOLBAR_BACKGROUND)
                addView(buildDrawingRibbon(), FrameLayout.LayoutParams(MATCH, MATCH))
            }
        rootLayout.addView(ribbonTabs, LinearLayout.LayoutParams(MATCH, dp(34)))
        rootLayout.addView(drawingRibbon, LinearLayout.LayoutParams(MATCH, dp(50)))

        val workArea =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(PAPER_COLOR)
            }
        navigationPane = buildNavigationPane()
        pagePane = buildPagePane()
        navigationRail = buildNavigationRail()
        workArea.addView(navigationRail, LinearLayout.LayoutParams(dp(32), MATCH))
        workArea.addView(navigationPane, LinearLayout.LayoutParams(dp(196), MATCH))
        workArea.addView(pagePane, LinearLayout.LayoutParams(dp(196), MATCH))
        workArea.addView(buildPaper(), LinearLayout.LayoutParams(0, MATCH, 1f))
        rootLayout.addView(workArea, LinearLayout.LayoutParams(MATCH, 0, 1f))

        applyChromeVisibility()
        return rootLayout
    }

    private fun showBackstage() {
        toolDetailPopup?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildBackstage(dialog))
        dialog.setOnDismissListener {
            if (backstageDialog === dialog) backstageDialog = null
        }
        backstageDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(TITLE_PURPLE))
            setLayout(MATCH, MATCH)
        }
    }

    private fun buildBackstage(dialog: Dialog): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(TITLE_PURPLE)

            val navigation =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(10))
                    setBackgroundColor(NAV_BACKGROUND)
                    addView(
                        backstageNavItem("←", false) { dialog.dismiss() },
                        LinearLayout.LayoutParams(MATCH, dp(42)),
                    )
                    addView(backstageNavItem("信息", true) {}, LinearLayout.LayoutParams(MATCH, dp(42)))
                    addView(backstageNavItem("新建", false) { showShell("新建笔记本") }, LinearLayout.LayoutParams(MATCH, dp(42)))
                    addView(backstageNavItem("打开", false) { showShell("打开笔记本") }, LinearLayout.LayoutParams(MATCH, dp(42)))
                    addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { setMargins(dp(4), dp(8), dp(4), dp(8)) })
                    addView(backstageNavItem("保存副本", false) { showShell("保存副本") }, LinearLayout.LayoutParams(MATCH, dp(42)))
                    addView(backstageNavItem("打印", false) { showShell("打印") }, LinearLayout.LayoutParams(MATCH, dp(42)))
                    addView(backstageNavItem("共享", false) { showShell("共享") }, LinearLayout.LayoutParams(MATCH, dp(42)))
                    addView(backstageNavItem("导出", false) { showShell("导出") }, LinearLayout.LayoutParams(MATCH, dp(42)))
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(MATCH, 0, 1f))
                    addView(backstageNavItem("帐户", false) { showShell("帐户") }, LinearLayout.LayoutParams(MATCH, dp(42)))
                    addView(backstageNavItem("选项", false) { showOptionsDialog() }, LinearLayout.LayoutParams(MATCH, dp(42)))
                }
            addView(navigation, LinearLayout.LayoutParams(dp(124), MATCH))

            val notebookList =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(30), dp(22), dp(30), dp(30))
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "笔记本信息"
                            setTextColor(TEXT_PRIMARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        },
                        LinearLayout.LayoutParams(MATCH, dp(46)),
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "选择要在工作区中打开的笔记本。备份、同步和共享将在后续版本接入。"
                            setTextColor(TEXT_SECONDARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        },
                        LinearLayout.LayoutParams(MATCH, dp(40)),
                    )
                    for ((index, notebook) in notebookLibrary.notebooks.withIndex()) {
                        addView(notebookInfoCard(notebook, index, dialog), LinearLayout.LayoutParams(MATCH, dp(92)).apply { bottomMargin = dp(12) })
                    }
                }
            addView(
                ScrollView(this@MainActivity).apply { addView(notebookList, ViewGroup.LayoutParams(MATCH, WRAP)) },
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
        }

    private fun backstageNavItem(label: String, selected: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            setTextColor(if (selected) TEXT_PRIMARY else TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            background =
                if (selected) rounded(SELECTED_LAVENDER, dp(2).toFloat(), ACCENT_PURPLE)
                else rounded(Color.TRANSPARENT, dp(2).toFloat(), Color.TRANSPARENT)
            setOnClickListener { action() }
        }

    private fun notebookInfoCard(notebook: NotebookWorkspace, index: Int, dialog: Dialog): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(31, 31, 31), dp(2).toFloat(), BORDER_COLOR)
            addView(
                ImageView(this@MainActivity).apply {
                    setImageDrawable(fluentIcon("notebook"))
                    imageTintList = ColorStateList.valueOf(sectionAccent(index))
                    background = rounded(Color.rgb(43, 43, 43), dp(2).toFloat(), BORDER_STRONG)
                    setPadding(dp(11), dp(11), dp(11), dp(11))
                },
                LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = notebook.title
                            setTextColor(TEXT_PRIMARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        },
                        LinearLayout.LayoutParams(MATCH, dp(30)),
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = if (notebook.id == notebookLibrary.activeNotebookId) "当前打开 · 本地工作区" else "本地工作区"
                            setTextColor(TEXT_SECONDARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        },
                        LinearLayout.LayoutParams(MATCH, dp(24)),
                    )
                },
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = if (notebook.id == notebookLibrary.activeNotebookId) "已打开" else "打开"
                    gravity = Gravity.CENTER
                    setTextColor(if (notebook.id == notebookLibrary.activeNotebookId) TEXT_SECONDARY else ACCENT_PURPLE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    background = rounded(Color.rgb(35, 35, 35), dp(2).toFloat(), BORDER_STRONG)
                    isEnabled = notebook.id != notebookLibrary.activeNotebookId
                    setOnClickListener {
                        notebookLibrary.selectNotebook(notebook.id)
                        clearContentSelection()
                        renderWorkspace()
                        dialog.dismiss()
                    }
                },
                LinearLayout.LayoutParams(dp(72), dp(36)),
            )
        }

    private fun showOptionsDialog() {
        backstageDialog?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(buildOptionsPage(dialog))
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.rgb(37, 37, 37)))
            setLayout(MATCH, MATCH)
        }
    }

    private fun buildOptionsPage(dialog: Dialog): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(37, 37, 37))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(14))
                    setBackgroundColor(NAV_BACKGROUND)
                    addView(backstageNavItem("←  返回", false) { dialog.dismiss() }, LinearLayout.LayoutParams(MATCH, dp(44)))
                    addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) })
                    addView(backstageNavItem("常规", true) {}, LinearLayout.LayoutParams(MATCH, dp(40)))
                    for (label in listOf("显示", "同步", "保存和备份", "语言", "高级")) {
                        addView(backstageNavItem(label, false) { showShell("$label 选项") }, LinearLayout.LayoutParams(MATCH, dp(40)))
                    }
                },
                LinearLayout.LayoutParams(dp(150), MATCH),
            )

            val settings =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(30), dp(22), dp(34), dp(28))
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "墨笺选项"
                            setTextColor(TEXT_PRIMARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        },
                        LinearLayout.LayoutParams(MATCH, dp(54)),
                    )
                    addView(optionsSectionTitle("绘图与页面"), LinearLayout.LayoutParams(MATCH, dp(36)))
                    addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)))

                    val predictionValue = settingsValueButton(if (predictionEnabled) "开启" else "关闭")
                    predictionValue.setOnClickListener {
                        predictionEnabled = !predictionEnabled
                        predictionValue.text = if (predictionEnabled) "开启" else "关闭"
                    }
                    addView(settingsRow("笔迹预测", "使用预测触点降低快速书写时的视觉延迟。", predictionValue), LinearLayout.LayoutParams(MATCH, dp(72)))

                    val pageModeValue = settingsValueButton(if (workspace.pagePaneMode == PagePaneMode.CURRENT_SECTION) "本层" else "递归")
                    pageModeValue.setOnClickListener {
                        val mode = if (workspace.pagePaneMode == PagePaneMode.CURRENT_SECTION) PagePaneMode.RECURSIVE else PagePaneMode.CURRENT_SECTION
                        notebookLibrary.setPagePaneMode(mode)
                        pageModeValue.text = if (mode == PagePaneMode.CURRENT_SECTION) "本层" else "递归"
                        renderPages()
                    }
                    addView(settingsRow("页面栏显示范围", "本层只显示当前分区页面；递归还显示所有子分区及其页面。", pageModeValue), LinearLayout.LayoutParams(MATCH, dp(72)))

                    addView(optionsSectionTitle("整页视图"), LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(12) })
                    addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)))
                    val toolbarValue = settingsValueButton("配置")
                    toolbarValue.setOnClickListener { showFullPageToolbarOptions() }
                    addView(
                        settingsRow(
                            "快捷工具栏",
                            "添加或移除任意开始、绘图按钮；整页视图中可横向滑动使用。",
                            toolbarValue,
                        ),
                        LinearLayout.LayoutParams(MATCH, dp(76)),
                    )
                }
            addView(
                ScrollView(this@MainActivity).apply {
                    isFillViewport = true
                    addView(settings, ViewGroup.LayoutParams(MATCH, WRAP))
                },
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
        }

    private fun showFullPageToolbarOptions() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun refresh() {
            dialog.setContentView(buildFullPageToolbarOptionsPage(dialog) { refresh() })
        }
        refresh()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.rgb(37, 37, 37)))
            setLayout(MATCH, MATCH)
        }
    }

    private fun buildFullPageToolbarOptionsPage(dialog: Dialog, refresh: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(37, 37, 37))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(18), 0)
                    setBackgroundColor(NAV_BACKGROUND)
                    addView(backstageNavItem("←  返回选项", false) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(150), dp(48)))
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "整页视图快捷工具栏"
                            gravity = Gravity.CENTER_VERTICAL
                            setTextColor(TEXT_PRIMARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        },
                        LinearLayout.LayoutParams(0, MATCH, 1f),
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "${fullPageToolbarConfig.ids.size} 个按钮"
                            gravity = Gravity.CENTER
                            setTextColor(TEXT_SECONDARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        },
                        LinearLayout.LayoutParams(dp(86), MATCH),
                    )
                },
                LinearLayout.LayoutParams(MATCH, dp(52)),
            )

            val catalog = fullPageToolCatalog()
            val body =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(26), dp(18), dp(26), dp(28))
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "点击“添加”会把按钮放到快捷栏末尾；点击“移除”会立即从整页视图隐藏。"
                            setTextColor(TEXT_SECONDARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        },
                        LinearLayout.LayoutParams(MATCH, dp(36)),
                    )
                    addView(optionsSectionTitle("当前按钮"), LinearLayout.LayoutParams(MATCH, dp(38)))
                    fullPageToolbarConfig.ids.mapNotNull { id -> catalog.firstOrNull { it.id == id } }.forEach { descriptor ->
                        addView(fullPageToolbarOptionRow(descriptor, selected = true, refresh), LinearLayout.LayoutParams(MATCH, dp(48)))
                    }
                    addView(optionsSectionTitle("可添加按钮"), LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(10) })
                    catalog.filterNot { fullPageToolbarConfig.contains(it.id) }.forEach { descriptor ->
                        addView(fullPageToolbarOptionRow(descriptor, selected = false, refresh), LinearLayout.LayoutParams(MATCH, dp(48)))
                    }
                }
            addView(
                ScrollView(this@MainActivity).apply { addView(body, ViewGroup.LayoutParams(MATCH, WRAP)) },
                LinearLayout.LayoutParams(MATCH, 0, 1f),
            )
        }

    private fun fullPageToolbarOptionRow(descriptor: FullPageToolDescriptor, selected: Boolean, refresh: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            background = rounded(if (selected) Color.rgb(43, 43, 43) else Color.TRANSPARENT, dp(2).toFloat(), BORDER_COLOR)
            addView(
                ImageView(this@MainActivity).apply {
                    setImageDrawable(fluentIcon(descriptor.icon))
                    imageTintList = ColorStateList.valueOf(if (descriptor.redAccent) CanvasTagLayer.TAG_RED else TEXT_PRIMARY)
                },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = descriptor.label
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                },
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = if (selected) "移除" else "添加"
                    gravity = Gravity.CENTER
                    setTextColor(if (selected) TEXT_SECONDARY else ACCENT_PURPLE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setOnClickListener {
                        if (selected) fullPageToolbarConfig.remove(descriptor.id) else fullPageToolbarConfig.add(descriptor.id)
                        persistFullPageToolbarIds()
                        renderFullPageToolbar()
                        refresh()
                    }
                },
                LinearLayout.LayoutParams(dp(64), dp(36)),
            )
        }

    private fun loadFullPageToolbarIds(): List<String> {
        val preferences = getSharedPreferences(OPTIONS_PREFERENCES, MODE_PRIVATE)
        if (!preferences.contains(FULL_PAGE_TOOLBAR_KEY)) return FullPageToolbarConfig.DEFAULT_IDS
        return preferences.getString(FULL_PAGE_TOOLBAR_KEY, "")
            .orEmpty()
            .split(TOOLBAR_ID_SEPARATOR)
            .filter(::isKnownFullPageToolId)
    }

    private fun isKnownFullPageToolId(id: String): Boolean =
        when {
            id in setOf("draw:undo", "draw:cursor", "draw:add", "draw:pan", "draw:clear") -> true
            id.startsWith("home:") -> HomeCommand.fromId(id.removePrefix("home:")) != null
            id.startsWith("tool:") -> toolShelf.tools.any { it.id == id.removePrefix("tool:") }
            else -> false
        }

    private fun persistFullPageToolbarIds() {
        getSharedPreferences(OPTIONS_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(FULL_PAGE_TOOLBAR_KEY, fullPageToolbarConfig.ids.joinToString(TOOLBAR_ID_SEPARATOR))
            .apply()
    }

    private fun optionsSectionTitle(label: String): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun settingsRow(title: String, description: String, valueView: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = title
                            setTextColor(TEXT_PRIMARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        },
                        LinearLayout.LayoutParams(MATCH, dp(28)),
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = description
                            setTextColor(TEXT_SECONDARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                        },
                        LinearLayout.LayoutParams(MATCH, dp(26)),
                    )
                },
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
            addView(valueView, LinearLayout.LayoutParams(dp(82), dp(36)))
        }

    private fun settingsValueButton(value: String): TextView =
        TextView(this).apply {
            text = value
            gravity = Gravity.CENTER
            setTextColor(ACCENT_PURPLE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = rounded(Color.rgb(47, 47, 47), dp(2).toFloat(), BORDER_STRONG)
        }

    private fun buildRibbonTabs(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(RIBBON_BACKGROUND)
            populateRibbonTabs(this)
        }

    private fun populateRibbonTabs(row: LinearLayout) {
        row.removeAllViews()
        row.addView(ribbonTab("文件", false) { showBackstage() })
        row.addView(ribbonTab("开始", activeRibbonPage == RibbonPage.HOME) { selectRibbonPage(RibbonPage.HOME) })
        row.addView(ribbonTab("插入", false) { showShell("插入工具") })
        row.addView(ribbonTab("绘图", activeRibbonPage == RibbonPage.DRAW) { selectRibbonPage(RibbonPage.DRAW) })
        row.addView(ribbonTab("视图", false) { showShell("视图工具") })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    }

    private fun selectRibbonPage(page: RibbonPage) {
        if (activeRibbonPage == page) {
            if (page == RibbonPage.HOME) setTextMode(true)
            return
        }
        activeRibbonPage = page
        populateRibbonTabs(ribbonTabs as LinearLayout)
        val host = drawingRibbon as FrameLayout
        host.removeAllViews()
        host.addView(
            if (page == RibbonPage.HOME) buildHomeRibbon() else buildDrawingRibbon(),
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        if (page == RibbonPage.HOME) {
            setTextMode(true, rerender = false)
        } else {
            renderToolShelf()
            renderToolDetails()
        }
    }

    private fun buildNavigationRail(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(TITLE_PURPLE)
            sectionPaneToggle = railToggleButton("navigation", "折叠或展开分区栏") { toggleSectionPane() }
            pagePaneToggle = railToggleButton("panel_left", "折叠或展开页面栏") { togglePagePane() }
            addView(sectionPaneToggle)
            addView(pagePaneToggle)
        }.also { it.background = bordered(TITLE_PURPLE, BORDER_COLOR, 0f) }

    private fun buildDrawingRibbon(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TOOLBAR_BACKGROUND)

            toolShelfRow =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(2), dp(8), dp(2))
                }
            addView(
                HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(toolShelfRow, ViewGroup.LayoutParams(WRAP, MATCH))
                },
                LinearLayout.LayoutParams(MATCH, dp(48)),
            )

            toolDetailRow =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setBackgroundColor(TOOLBAR_BACKGROUND)
                }
        }

    private fun buildHomeRibbon(): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(TOOLBAR_BACKGROUND)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(2), dp(8), dp(2))

                    addHomeCommand(HomeCommand.PASTE)
                    addHomeCommand(HomeCommand.CUT)
                    addHomeCommand(HomeCommand.COPY)
                    addHomeCommand(HomeCommand.FORMAT_PAINTER)
                    addView(verticalDivider())

                    addView(homeDropdownButton("无衬线", HomeCommand.FONT_FAMILY), LinearLayout.LayoutParams(dp(90), dp(38)))
                    addView(homeDropdownButton("16", HomeCommand.FONT_SIZE), LinearLayout.LayoutParams(dp(52), dp(38)).apply { marginStart = dp(4) })
                    for (
                        command in
                            listOf(
                                HomeCommand.BOLD,
                                HomeCommand.ITALIC,
                                HomeCommand.UNDERLINE,
                                HomeCommand.STRIKETHROUGH,
                                HomeCommand.FONT_COLOR,
                                HomeCommand.HIGHLIGHT,
                                HomeCommand.SUBSCRIPT,
                                HomeCommand.CLEAR_FORMATTING,
                            )
                    ) addHomeCommand(command)
                    addView(verticalDivider())

                    for (
                        command in
                            listOf(
                                HomeCommand.BULLETS,
                                HomeCommand.NUMBERING,
                                HomeCommand.INDENT_DECREASE,
                                HomeCommand.INDENT_INCREASE,
                                HomeCommand.ALIGN_LEFT,
                                HomeCommand.ALIGN_CENTER,
                                HomeCommand.ALIGN_RIGHT,
                                HomeCommand.LINE_SPACING,
                            )
                    ) addHomeCommand(command)
                    addView(verticalDivider())

                    addHomeCommand(HomeCommand.TAG, redAccent = true)
                    addHomeCommand(HomeCommand.FIND_TAGS, redAccent = true)
                    addView(verticalDivider())
                    addView(homeDropdownButton("样式", HomeCommand.STYLES), LinearLayout.LayoutParams(dp(70), dp(38)))
                },
                ViewGroup.LayoutParams(WRAP, MATCH),
            )
        }

    private fun LinearLayout.addHomeCommand(command: HomeCommand, redAccent: Boolean = false) {
        addView(homeCommandButton(command, redAccent), LinearLayout.LayoutParams(dp(36), dp(42)))
    }

    private fun homeCommandButton(command: HomeCommand, redAccent: Boolean = command == HomeCommand.TAG || command == HomeCommand.FIND_TAGS): View =
        FrameLayout(this).apply {
            background = rounded(Color.TRANSPARENT, 0f, Color.TRANSPARENT)
            addView(
                ImageView(this@MainActivity).apply {
                    setImageDrawable(fluentIcon(command.icon))
                    imageTintList = ColorStateList.valueOf(if (redAccent) CanvasTagLayer.TAG_RED else TEXT_PRIMARY)
                },
                FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER),
            )
            setOnClickListener { performHomeCommand(command, this) }
            contentDescription = command.label
            tooltipText = command.label
        }

    private fun homeDropdownButton(label: String, command: HomeCommand): TextView =
        TextView(this).apply {
            text = "$label  ⌄"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(5), 0)
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            background = rounded(Color.rgb(42, 42, 42), dp(2).toFloat(), BORDER_STRONG)
            setOnClickListener { performHomeCommand(command, this) }
            contentDescription = command.label
            tooltipText = command.label
        }

    private fun performHomeCommand(command: HomeCommand, anchor: View) {
        if (command != HomeCommand.TAG && command != HomeCommand.FIND_TAGS) {
            setTextMode(true, rerender = false)
            renderFullPageToolbar()
        }
        when (command) {
            HomeCommand.PASTE -> runTextCommand("粘贴") { textInputLayer.paste() }
            HomeCommand.CUT -> runTextCommand("剪切") { textInputLayer.cut() }
            HomeCommand.COPY -> runTextCommand("复制") { textInputLayer.copy() }
            HomeCommand.FORMAT_PAINTER ->
                when (textInputLayer.formatPainter()) {
                    FormatPainterResult.CAPTURED -> Toast.makeText(this, "已复制格式；选择目标文字后再次点击格式刷", Toast.LENGTH_SHORT).show()
                    FormatPainterResult.APPLIED -> Toast.makeText(this, "已应用格式", Toast.LENGTH_SHORT).show()
                    FormatPainterResult.UNAVAILABLE -> showTextSelectionHint()
                }
            HomeCommand.FONT_FAMILY ->
                showTextChoiceMenu(
                    anchor,
                    listOf("无衬线" to { textInputLayer.applyFontFamily("sans-serif") }, "衬线" to { textInputLayer.applyFontFamily("serif") }, "等宽" to { textInputLayer.applyFontFamily("monospace") }),
                )
            HomeCommand.FONT_SIZE ->
                showTextChoiceMenu(anchor, listOf(10, 12, 14, 16, 18, 24, 32).map { size -> "$size" to { textInputLayer.applyFontSize(size) } })
            HomeCommand.BOLD -> runTextCommand("加粗") { textInputLayer.toggleBold() }
            HomeCommand.ITALIC -> runTextCommand("倾斜") { textInputLayer.toggleItalic() }
            HomeCommand.UNDERLINE -> runTextCommand("下划线") { textInputLayer.toggleUnderline() }
            HomeCommand.STRIKETHROUGH -> runTextCommand("删除线") { textInputLayer.toggleStrikethrough() }
            HomeCommand.FONT_COLOR ->
                showTextChoiceMenu(
                    anchor,
                    listOf(
                        "白色" to { textInputLayer.applyForegroundColor(TEXT_PRIMARY) },
                        "红色" to { textInputLayer.applyForegroundColor(CanvasTagLayer.TAG_RED) },
                        "蓝色" to { textInputLayer.applyForegroundColor(Color.rgb(86, 156, 214)) },
                        "黄色" to { textInputLayer.applyForegroundColor(Color.rgb(255, 214, 10)) },
                    ),
                )
            HomeCommand.HIGHLIGHT ->
                showTextChoiceMenu(
                    anchor,
                    listOf(
                        "黄色" to { textInputLayer.applyBackgroundColor(Color.rgb(104, 88, 0)) },
                        "绿色" to { textInputLayer.applyBackgroundColor(Color.rgb(24, 92, 54)) },
                        "红色" to { textInputLayer.applyBackgroundColor(Color.rgb(111, 37, 42)) },
                        "无" to { textInputLayer.applyBackgroundColor(Color.TRANSPARENT) },
                    ),
                )
            HomeCommand.SUBSCRIPT -> runTextCommand("下标") { textInputLayer.toggleSubscript() }
            HomeCommand.CLEAR_FORMATTING -> runTextCommand("清除格式") { textInputLayer.clearFormatting() }
            HomeCommand.BULLETS -> runTextCommand("项目符号") { textInputLayer.applyBullets() }
            HomeCommand.NUMBERING -> runTextCommand("编号") { textInputLayer.applyNumbering() }
            HomeCommand.INDENT_DECREASE -> runTextCommand("减少缩进") { textInputLayer.changeIndent(false) }
            HomeCommand.INDENT_INCREASE -> runTextCommand("增加缩进") { textInputLayer.changeIndent(true) }
            HomeCommand.ALIGN_LEFT -> runTextCommand("左对齐") { textInputLayer.align(Layout.Alignment.ALIGN_NORMAL) }
            HomeCommand.ALIGN_CENTER -> runTextCommand("居中") { textInputLayer.align(Layout.Alignment.ALIGN_CENTER) }
            HomeCommand.ALIGN_RIGHT -> runTextCommand("右对齐") { textInputLayer.align(Layout.Alignment.ALIGN_OPPOSITE) }
            HomeCommand.LINE_SPACING ->
                showTextChoiceMenu(anchor, listOf(1f, 1.15f, 1.5f, 2f).map { spacing -> "${spacing} 倍" to { textInputLayer.setLineSpacing(spacing) } })
            HomeCommand.TAG -> showTagMenu(anchor)
            HomeCommand.FIND_TAGS -> showFindTags(anchor)
            HomeCommand.STYLES ->
                showTextChoiceMenu(anchor, listOf("正文", "标题", "标题 1", "标题 2").map { style -> style to { textInputLayer.applyNamedStyle(style) } })
        }
    }

    private fun runTextCommand(label: String, action: () -> Boolean) {
        if (!action()) showTextSelectionHint() else Toast.makeText(this, "已$label", Toast.LENGTH_SHORT).show()
    }

    private fun showTextSelectionHint() {
        Toast.makeText(this, "请先点击页面创建文本框，并选择文字", Toast.LENGTH_SHORT).show()
    }

    private fun showTextChoiceMenu(anchor: View, choices: List<Pair<String, () -> Boolean>>) {
        PopupMenu(this, anchor).apply {
            choices.forEach { (label, action) ->
                menu.add(label).setOnMenuItemClickListener {
                    if (!action()) showTextSelectionHint()
                    true
                }
            }
            show()
        }
    }

    private fun showTagMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            CanvasTagKind.entries.forEach { kind ->
                menu.add(kind.label).setOnMenuItemClickListener {
                    beginTagPlacement(kind)
                    true
                }
            }
            show()
        }
    }

    private fun beginTagPlacement(kind: CanvasTagKind) {
        restoreTextModeAfterTagPlacement = textModeEnabled
        setTextMode(false, rerender = false)
        singleFingerPanEnabled = false
        transformGestureActive = false
        tagLayer.beginPlacement(kind)
        selectionOverlay.selectionEnabled = false
        tagLayer.selectionModeEnabled = false
        Toast.makeText(this, "点击页面放置${kind.label}标记", Toast.LENGTH_SHORT).show()
    }

    private fun showFindTags(anchor: View) {
        val tags = tagLayer.allTags()
        if (tags.isEmpty()) {
            Toast.makeText(this, "当前页面还没有标记", Toast.LENGTH_SHORT).show()
            return
        }
        PopupMenu(this, anchor).apply {
            tags.forEachIndexed { index, tag ->
                menu.add("${index + 1}. ${tag.kind.label}").setOnMenuItemClickListener {
                    viewport.centerDocumentPoint(tag.documentX, tag.documentY, tagLayer.width * 0.5f, tagLayer.height * 0.5f)
                    dryInkView.setViewport(viewport.documentToViewMatrix())
                    tagLayer.invalidate()
                    selectionOverlay.invalidate()
                    tagLayer.focusTag(index)
                    true
                }
            }
            show()
        }
    }

    private fun buildNavigationPane(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAV_BACKGROUND)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(7), dp(4))
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(
                        titleIconButton("add", "新建笔记本或分区") { anchor -> showNavigationAddMenu(anchor) },
                        LinearLayout.LayoutParams(dp(38), dp(38)),
                    )
                },
                LinearLayout.LayoutParams(MATCH, dp(44)),
            )

            sectionList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(
                ScrollView(this@MainActivity).apply {
                    isFillViewport = true
                    addView(sectionList, ViewGroup.LayoutParams(MATCH, WRAP))
                    setOnClickListener {
                        workspace.clearSectionFocus()
                        renderSections()
                    }
                },
                LinearLayout.LayoutParams(MATCH, 0, 1f),
            )
        }.also { it.background = bordered(NAV_BACKGROUND, BORDER_COLOR, 0f) }

    private fun buildPagePane(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE_LIST_BACKGROUND)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(6), 0)
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    addPageButton =
                        titleIconButton("add", "新建页面") { _ ->
                            if (workspace.hasSelectedSection()) {
                                workspace.addPage()
                                renderPages()
                            } else {
                                Toast.makeText(this@MainActivity, "笔记本下无法直接创建页面，请先在左侧选择或新建分区", Toast.LENGTH_SHORT).show()
                            }
                        }
                    addView(
                        addPageButton,
                        LinearLayout.LayoutParams(dp(38), dp(38)),
                    )
                },
                LinearLayout.LayoutParams(MATCH, dp(48)),
            )
            pageList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(
                ScrollView(this@MainActivity).apply {
                    isFillViewport = true
                    addView(pageList, ViewGroup.LayoutParams(MATCH, WRAP))
                },
                LinearLayout.LayoutParams(MATCH, 0, 1f),
            )
        }.also { it.background = bordered(PAGE_LIST_BACKGROUND, BORDER_COLOR, 0f) }

    private fun buildPaper(): View {
        val paper = FrameLayout(this).apply { setBackgroundColor(PAPER_COLOR) }
        dryInkView = DryInkView(this)
        wetInkView = InProgressStrokesView(this)
        tagLayer = CanvasTagLayer(this, viewport)
        textInputLayer = CanvasTextInputLayer(this)
        selectionOverlay =
            SelectionOverlayView(this, viewport).apply {
                selectionBoundsProvider = ::combinedSelectionBounds
                onTranslateSelection = { deltaX, deltaY ->
                    dryInkView.translateSelection(deltaX, deltaY)
                    tagLayer.translateSelection(deltaX, deltaY)
                }
                onScaleSelection = { anchorX, anchorY, factor ->
                    dryInkView.scaleSelection(anchorX, anchorY, factor)
                    tagLayer.scaleSelection(anchorX, anchorY, factor)
                }
                onTransformFinished = { renderToolDetails() }
            }
        tagLayer.onPlacementFinished = { tag ->
            if (restoreTextModeAfterTagPlacement) setTextMode(true)
            restoreTextModeAfterTagPlacement = false
            updateSelectionInteraction()
            Toast.makeText(this, "已放置${tag.kind.label}标记", Toast.LENGTH_SHORT).show()
            renderFullPageToolbar()
        }
        tagLayer.onTagClicked = {
            dryInkView.clearSelection()
            selectionOverlay.invalidate()
            renderToolDetails()
        }
        dryInkView.setViewport(viewport.documentToViewMatrix())
        paper.addView(dryInkView, FrameLayout.LayoutParams(MATCH, MATCH))
        paper.addView(wetInkView, FrameLayout.LayoutParams(MATCH, MATCH))
        paper.addView(textInputLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        paper.addView(tagLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        paper.addView(selectionOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        floatingToolbox = buildFloatingToolbox()
        paper.addView(
            floatingToolbox,
            FrameLayout.LayoutParams(MATCH, dp(48), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(7)
                leftMargin = dp(116)
                rightMargin = dp(116)
            },
        )
        paper.addView(
            buildCanvasActions(),
            FrameLayout.LayoutParams(WRAP, dp(36), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(6)
                rightMargin = dp(7)
            },
        )
        return paper
    }

    private fun buildFloatingToolbox(): View =
        FrameLayout(this).apply {
            elevation = dp(7).toFloat()
            background = rounded(TOOLBAR_BACKGROUND, dp(3).toFloat(), BORDER_STRONG)
            floatingToolShelfRow =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                }
            addView(
                HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(floatingToolShelfRow, ViewGroup.LayoutParams(WRAP, MATCH))
                },
                FrameLayout.LayoutParams(MATCH, MATCH),
            )
        }

    private fun buildCanvasActions(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(8).toFloat()

            toolbarToggle = canvasActionButton("pen", "显示或隐藏绘图工具栏", selected = true) {
                drawingToolbarVisible = !drawingToolbarVisible
                applyChromeVisibility()
            }
            addView(toolbarToggle, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) })
            addView(
                canvasActionButton("search", "搜索笔记", selected = false) { showShell("全文搜索") },
                LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) },
            )
            fullPageToggle = canvasActionButton("full_screen_maximize", "进入整页视图", selected = false) {
                fullPageMode = !fullPageMode
                applyChromeVisibility()
            }
            fullPageToggleIcon = (fullPageToggle as ViewGroup).getChildAt(0) as ImageView
            addView(fullPageToggle, LinearLayout.LayoutParams(dp(34), dp(34)))
        }

    private fun renderWorkspace() {
        renderSections()
        renderPages()
    }

    private fun renderSections() {
        sectionList.removeAllViews()
        for ((notebookIndex, notebook) in notebookLibrary.notebooks.withIndex()) {
            val activeNotebook = notebook.id == notebookLibrary.activeNotebookId
            val notebookFocused = activeNotebook && notebook.focusedSectionId == null
            val notebookRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(5), 0, dp(7), 0)
                    background =
                        if (notebookFocused) rounded(SELECTED_LAVENDER, dp(2).toFloat(), SELECTED_LAVENDER)
                        else rounded(Color.TRANSPARENT, dp(2).toFloat(), Color.TRANSPARENT)
                    setOnClickListener {
                        notebookLibrary.selectNotebook(notebook.id)
                        notebook.focusNotebook()
                        clearContentSelection()
                        renderWorkspace()
                    }
                }
            notebookRow.addView(
                TextView(this).apply {
                    text = if (notebook.expanded) "⌄" else "›"
                    gravity = Gravity.CENTER
                    setTextColor(TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setOnClickListener {
                        notebook.expanded = !notebook.expanded
                        renderSections()
                    }
                },
                LinearLayout.LayoutParams(dp(24), MATCH),
            )
            notebookRow.addView(
                ImageView(this).apply {
                    setImageDrawable(fluentIcon("notebook"))
                    imageTintList = ColorStateList.valueOf(sectionAccent(notebookIndex))
                },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) },
            )
            notebookRow.addView(
                TextView(this).apply {
                    text = notebook.title
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(if (activeNotebook) TEXT_PRIMARY else TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = if (activeNotebook) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                    maxLines = 1
                },
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
            sectionList.addView(notebookRow, LinearLayout.LayoutParams(MATCH, dp(40)).apply { setMargins(dp(5), dp(1), dp(5), dp(1)) })

            if (!notebook.expanded) continue
            for (row in notebook.flattenSections()) {
                val section = row.section
                val selected = activeNotebook && section.id == notebook.focusedSectionId
                val item =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14 + row.depth * 15), 0, dp(7), 0)
                        background =
                            if (selected) rounded(SELECTED_LAVENDER, dp(2).toFloat(), SELECTED_LAVENDER)
                            else rounded(Color.TRANSPARENT, dp(2).toFloat(), Color.TRANSPARENT)
                        setOnClickListener {
                            notebookLibrary.selectNotebook(notebook.id)
                            notebook.selectSection(section.id)
                            clearContentSelection()
                            renderWorkspace()
                        }
                    }
                val chevron =
                    TextView(this).apply {
                        text =
                            when {
                                section.children.isEmpty() -> ""
                                section.expanded -> "⌄"
                                else -> "›"
                            }
                        gravity = Gravity.CENTER
                        setTextColor(TEXT_SECONDARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        setOnClickListener {
                            notebook.toggleExpanded(section.id)
                            renderSections()
                        }
                    }
                item.addView(chevron, LinearLayout.LayoutParams(dp(24), MATCH))
                item.addView(
                    View(this).apply {
                        background = rounded(sectionAccent(row.depth + notebookIndex + 1), dp(2).toFloat(), sectionAccent(row.depth + notebookIndex + 1))
                    },
                    LinearLayout.LayoutParams(dp(4), dp(22)).apply { marginEnd = dp(8) },
                )
                item.addView(
                    TextView(this).apply {
                        text = section.title
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                        maxLines = 1
                    },
                    LinearLayout.LayoutParams(0, MATCH, 1f),
                )
                sectionList.addView(item, LinearLayout.LayoutParams(MATCH, dp(40)).apply { setMargins(dp(5), dp(1), dp(5), dp(1)) })
            }
        }
    }

    private fun renderPages() {
        val canAdd = workspace.hasSelectedSection()
        if (::addPageButton.isInitialized) {
            addPageButton.isEnabled = canAdd
            addPageButton.alpha = if (canAdd) 1f else 0.35f
            addPageButton.compoundDrawableTintList =
                ColorStateList.valueOf(if (canAdd) TEXT_PRIMARY else Color.rgb(120, 120, 120))
        }
        pageList.removeAllViews()
        val rows = workspace.pagePaneRows()
        if (rows.isEmpty()) {
            pageList.addView(
                TextView(this).apply {
                    text =
                        if (workspace.hasSelectedSection()) "这里还没有页面\n点击右上角 + 新建"
                        else if (workspace.sections.isEmpty()) "这个笔记本还没有分区\n请从左侧 + 新建分区"
                        else "未选择分区\n请在左侧选择分区以查看页面"
                    setTextColor(TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(44), dp(12), dp(20))
                },
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
            return
        }
        for (row in rows) {
            when (row) {
                is PagePaneRow.SectionHeader -> addRecursiveSectionHeader(row)
                is PagePaneRow.PageItem -> addPageRow(row)
            }
        }
    }

    private fun addRecursiveSectionHeader(row: PagePaneRow.SectionHeader) {
        val item =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9 + row.depth * 13), 0, dp(8), 0)
                setBackgroundColor(Color.rgb(35, 35, 35))
                addView(
                    View(this@MainActivity).apply {
                        background = rounded(sectionAccent(row.depth + 1), dp(1).toFloat(), sectionAccent(row.depth + 1))
                    },
                    LinearLayout.LayoutParams(dp(3), dp(18)).apply { marginEnd = dp(8) },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = row.section.title
                        setTextColor(TEXT_SECONDARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        maxLines = 1
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(0, MATCH, 1f),
                )
            }
        pageList.addView(item, LinearLayout.LayoutParams(MATCH, dp(32)).apply { setMargins(dp(6), dp(5), dp(6), 0) })
    }

    private fun addPageRow(row: PagePaneRow.PageItem) {
        val page = row.page
        val selected = page.id == workspace.selectedPageId && row.sectionId == workspace.selectedPageSectionId
        val item =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13 + row.depth * 13), dp(7), dp(10), dp(6))
                background =
                    if (selected) rounded(SELECTED_LAVENDER, dp(2).toFloat(), SELECTED_LAVENDER)
                    else rounded(Color.TRANSPARENT, dp(2).toFloat(), Color.TRANSPARENT)
                addView(
                    TextView(this@MainActivity).apply {
                        text = page.title
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                        maxLines = 1
                    },
                    LinearLayout.LayoutParams(MATCH, dp(22)),
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = page.subtitle
                        setTextColor(TEXT_SECONDARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    },
                    LinearLayout.LayoutParams(MATCH, dp(18)),
                )
                setOnClickListener {
                    workspace.selectPage(row.sectionId, page.id)
                    renderPages()
                }
            }
        pageList.addView(item, LinearLayout.LayoutParams(MATCH, dp(58)).apply { setMargins(dp(6), dp(1), dp(6), dp(1)) })
    }

    private fun renderToolShelf() {
        populateToolShelf(toolShelfRow)
        renderFullPageToolbar()
    }

    private fun renderFullPageToolbar() {
        if (!::floatingToolShelfRow.isInitialized) return
        floatingToolShelfRow.removeAllViews()
        fullPageToolbarConfig.ids.forEach { id ->
            fullPageToolView(id)?.let { floatingToolShelfRow.addView(it) }
        }
    }

    private fun fullPageToolCatalog(): List<FullPageToolDescriptor> =
        buildList {
            add(FullPageToolDescriptor("draw:undo", "撤销", "arrow_undo"))
            add(FullPageToolDescriptor("draw:cursor", "键入文本", "cursor"))
            toolShelf.tools.forEach { tool ->
                val icon =
                    when (tool.kind) {
                        DrawingToolKind.PEN -> "pen"
                        DrawingToolKind.HIGHLIGHTER -> "highlight"
                        DrawingToolKind.ERASER -> "eraser"
                        DrawingToolKind.LASSO -> "lasso"
                    }
                add(FullPageToolDescriptor("tool:${tool.id}", tool.displayName, icon))
            }
            add(FullPageToolDescriptor("draw:add", "添加绘图工具", "add"))
            add(FullPageToolDescriptor("draw:pan", "单指平移", "hand_left"))
            add(FullPageToolDescriptor("draw:clear", "清纸", "delete_dismiss"))
            HomeCommand.entries.forEach { command ->
                add(
                    FullPageToolDescriptor(
                        id = "home:${command.id}",
                        label = command.label,
                        icon = command.icon,
                        redAccent = command == HomeCommand.TAG || command == HomeCommand.FIND_TAGS,
                    ),
                )
            }
        }

    private fun fullPageToolView(id: String): View? {
        if (id.startsWith("home:")) {
            val command = HomeCommand.fromId(id.removePrefix("home:")) ?: return null
            return homeCommandButton(command).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(42))
            }
        }
        if (id.startsWith("tool:")) {
            val tool = toolShelf.tools.firstOrNull { it.id == id.removePrefix("tool:") } ?: return null
            return toolButton(tool)
        }
        return when (id) {
            "draw:undo" ->
                commandButton("arrow_undo", "撤销", false) {
                    clearContentSelection()
                    dryInkView.undo()
                }
            "draw:cursor" -> commandButton("cursor", "键入文本", textModeEnabled) { setTextMode(!textModeEnabled) }
            "draw:add" -> commandButton("add", "添加绘图工具", false) { anchor -> showAddToolMenu(anchor) }
            "draw:pan" ->
                commandButton("hand_left", "单指平移", singleFingerPanEnabled) {
                    setTextMode(false, rerender = false)
                    tagLayer.cancelPlacement()
                    singleFingerPanEnabled = !singleFingerPanEnabled
                    transformGestureActive = false
                    renderToolShelf()
                }
            "draw:clear" -> commandButton("delete_dismiss", "清纸", false) { confirmClearPage() }
            else -> null
        }?.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(42)) }
    }

    private fun populateToolShelf(row: LinearLayout) {
        row.removeAllViews()
        row.addView(
            commandButton("arrow_undo", "撤销", selected = false) {
                clearContentSelection()
                dryInkView.undo()
            },
            LinearLayout.LayoutParams(dp(40), dp(42)),
        )
        row.addView(verticalDivider())
        row.addView(
            commandButton("cursor", "键入文本", selected = textModeEnabled) {
                setTextMode(!textModeEnabled)
            },
            LinearLayout.LayoutParams(dp(40), dp(42)),
        )
        toolShelf.tools.filter { it.kind == DrawingToolKind.LASSO }.forEach { row.addView(toolButton(it)) }
        row.addView(verticalDivider())
        toolShelf.tools.filter { it.kind == DrawingToolKind.ERASER }.forEach { row.addView(toolButton(it)) }
        toolShelf.tools.filter { it.kind == DrawingToolKind.HIGHLIGHTER }.forEach { row.addView(toolButton(it)) }
        toolShelf.tools.filter { it.kind == DrawingToolKind.PEN }.forEach { row.addView(toolButton(it)) }
        row.addView(
            commandButton("add", "添加", selected = false) { anchor -> showAddToolMenu(anchor) },
            LinearLayout.LayoutParams(dp(40), dp(42)).apply { marginEnd = dp(3) },
        )
        row.addView(verticalDivider())
        row.addView(
            commandButton("hand_left", "单指平移", selected = singleFingerPanEnabled) {
                setTextMode(false, rerender = false)
                tagLayer.cancelPlacement()
                singleFingerPanEnabled = !singleFingerPanEnabled
                transformGestureActive = false
                toolDetailPopup?.dismiss()
                toolDetailPopup = null
                renderToolShelf()
            },
            LinearLayout.LayoutParams(dp(40), dp(42)),
        )
        row.addView(verticalDivider())
        row.addView(
            commandButton("delete_dismiss", "清纸", selected = false) { confirmClearPage() },
            LinearLayout.LayoutParams(dp(40), dp(42)),
        )
    }

    private fun renderToolDetails() {
        toolDetailRow.removeAllViews()
        val tool = toolShelf.selected
        toolDetailRow.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val iconName =
                    when (tool.kind) {
                        DrawingToolKind.PEN -> "pen"
                        DrawingToolKind.HIGHLIGHTER -> "highlight"
                        DrawingToolKind.ERASER -> "eraser"
                        DrawingToolKind.LASSO -> "lasso"
                    }
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageDrawable(fluentIcon(iconName))
                        imageTintList = ColorStateList.valueOf(if (tool.kind == DrawingToolKind.PEN || tool.kind == DrawingToolKind.HIGHLIGHTER) displayColor(tool.color) else TEXT_PRIMARY)
                    },
                    LinearLayout.LayoutParams(dp(25), dp(25)).apply { marginEnd = dp(9) },
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = tool.displayName
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(0, MATCH, 1f),
                )
            },
            LinearLayout.LayoutParams(MATCH, dp(38)),
        )
        toolDetailRow.addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)))

        when (tool.kind) {
            DrawingToolKind.PEN -> {
                addPaletteControls(tool)
                toolDetailRow.addView(
                    parameterControl("笔宽", ((tool.widthMm * 10).roundToInt() - 1).coerceIn(0, 19), 19, { "${(it + 1) / 10f} mm" }) {
                        tool.widthMm = (it + 1) / 10f
                        rebuildBrush()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                toolDetailRow.addView(
                    parameterControl("压感", tool.pressurePercent, 400, { "$it%" }) {
                        tool.pressurePercent = it
                        rebuildBrush()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                toolDetailRow.addView(
                    parameterControl("出墨", tool.inkFlowPercent, 200, { "$it%" }) {
                        tool.inkFlowPercent = it
                        rebuildBrush()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                addToolDetailLine(
                    detailChip(if (tool.pressureEraseEnabled) "压感橡皮 开" else "压感橡皮 关", tool.pressureEraseEnabled) {
                        tool.pressureEraseEnabled = !tool.pressureEraseEnabled
                        syncPressureEraserWithTool()
                        renderToolDetails()
                    },
                )
                toolDetailRow.addView(
                    parameterControl("阈值", ((pressureEraser.pressureThreshold * 100).roundToInt() - 40).coerceIn(0, 60), 60, { "${it + 40}%" }) {
                        pressureEraser.pressureThreshold = (it + 40) / 100f
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
            }

            DrawingToolKind.HIGHLIGHTER -> {
                addPaletteControls(tool)
                toolDetailRow.addView(
                    parameterControl("宽度", ((tool.widthMm - 2f) * 2f).roundToInt().coerceIn(0, 20), 20, { "${2f + it / 2f} mm" }) {
                        tool.widthMm = 2f + it / 2f
                        rebuildBrush()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                toolDetailRow.addView(
                    parameterControl("透明度", (tool.opacityPercent - 10).coerceIn(0, 60), 60, { "${it + 10}%" }) {
                        tool.opacityPercent = it + 10
                        rebuildBrush()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
            }

            DrawingToolKind.ERASER -> {
                toolDetailRow.addView(
                    parameterControl("大小", ((tool.eraserRadiusMm - 2f) * 2f).roundToInt().coerceIn(0, 28), 28, { "${2f + it / 2f} mm" }) {
                        tool.eraserRadiusMm = 2f + it / 2f
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                addToolDetailLine(detailChip("整笔擦除", true) { }, detailHint("像素擦除稍后接入"))
            }

            DrawingToolKind.LASSO -> {
                addToolDetailLine(detailHint("闭合套索可同时选择笔迹和标记"))
                addToolDetailLine(
                    detailChip("删除所选 ${selectedContentCount()}", selectedContentCount() > 0) {
                        val removed = deleteContentSelection()
                        if (removed == 0) Toast.makeText(this, "请先选择笔迹或标记", Toast.LENGTH_SHORT).show()
                        renderToolDetails()
                    },
                )
                addToolDetailLine(detailHint("在框内按住拖动；拖动四角缩放"))
            }
        }

        toolDetailRow.addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(4) })
        addToolDetailLine(
            detailChip("删除工具", false) {
                val removedToolId = toolShelf.selectedId
                if (!toolShelf.deleteSelected()) {
                    Toast.makeText(this, "至少保留一个工具", Toast.LENGTH_SHORT).show()
                    return@detailChip
                }
                fullPageToolbarConfig.remove("tool:$removedToolId")
                persistFullPageToolbarIds()
                clearContentSelection()
                if (removedToolId == lastPenToolId) {
                    toolShelf.tools.firstOrNull { it.kind == DrawingToolKind.PEN }?.let { lastPenToolId = it.id }
                }
                toolDetailPopup?.dismiss()
                toolDetailPopup = null
                rebuildBrush()
                renderToolShelf()
                renderToolDetails()
            },
        )
    }

    private fun addPaletteControls(tool: ToolPreset) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(detailHint("颜色"))
                for (color in InkPalette.entries) {
                    addView(colorButton(color, color == tool.color) {
                        tool.color = color
                        rebuildBrush()
                        renderToolShelf()
                        renderToolDetails()
                    })
                }
            }
        toolDetailRow.addView(row, LinearLayout.LayoutParams(MATCH, dp(42)))
    }

    private fun addToolDetailLine(vararg children: View) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                for (child in children) addView(child)
            }
        toolDetailRow.addView(row, LinearLayout.LayoutParams(MATCH, dp(42)))
    }

    private fun showToolDetails(anchor: View) {
        toolDetailPopup?.dismiss()
        (toolDetailRow.parent as? ViewGroup)?.removeView(toolDetailRow)
        renderToolDetails()
        val scroll =
            ScrollView(this).apply {
                isFillViewport = true
                addView(toolDetailRow, ViewGroup.LayoutParams(MATCH, WRAP))
                background = rounded(TOOLBAR_BACKGROUND, dp(3).toFloat(), BORDER_STRONG)
            }
        val popup =
            PopupWindow(scroll, dp(316), WRAP, true).apply {
                isOutsideTouchable = true
                setBackgroundDrawable(rounded(TOOLBAR_BACKGROUND, dp(3).toFloat(), BORDER_STRONG))
                elevation = dp(10).toFloat()
                setOnDismissListener { if (toolDetailPopup === this) toolDetailPopup = null }
            }
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val maxX = (resources.displayMetrics.widthPixels - dp(316) - dp(8)).coerceAtLeast(dp(8))
        val x = (location[0] - dp(8)).coerceIn(dp(8), maxX)
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, location[1] + anchor.height + dp(2))
        toolDetailPopup = popup
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindInkPipeline() {
        predictor = MotionEventPredictor.newInstance(wetInkView)
        wetInkView.addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    dryInkView.addStrokes(strokes.values)
                    dryInkView.invalidate()
                    wetInkView.removeFinishedStrokes(strokes.keys)
                }
            },
        )
        wetInkView.setOnTouchListener { _, event ->
            handleMotionEvent(event)
            true
        }
    }

    private fun handleMotionEvent(event: MotionEvent) {
        if (handleFingerGesture(event)) return
        if (!hasStylusPointer(event) && activeStrokes.isEmpty()) return
        predictor.record(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> beginStylusAction(event)
            MotionEvent.ACTION_MOVE -> continueStylusAction(event)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> finishStylusAction(event)
            MotionEvent.ACTION_CANCEL -> cancelStylusAction()
        }
    }

    private fun beginStylusAction(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        if (!isStylus(event.getToolType(pointerIndex))) return
        val pointerId = event.getPointerId(pointerIndex)
        pressurePointerId = pointerId
        lastPressure = event.getPressure(pointerIndex)
        lastPressurePoint = viewport.viewToDocument(event.getX(pointerIndex), event.getY(pointerIndex))
        val hardwareEraser = event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER
        val directPreset =
            when {
                hardwareEraser -> null
                textModeEnabled -> cursorModePenPreset()
                else -> toolShelf.selected
            }
        activeDirectTool = if (hardwareEraser) DrawingToolKind.ERASER else directPreset?.kind ?: DrawingToolKind.PEN

        when (activeDirectTool) {
            DrawingToolKind.PEN,
            DrawingToolKind.HIGHLIGHTER -> {
                clearContentSelection()
                pressureEraser.enabled =
                    activeDirectTool == DrawingToolKind.PEN && (directPreset?.pressureEraseEnabled ?: false)
                activeStrokes[pointerId] =
                    wetInkView.startStroke(
                        event = event,
                        pointerId = pointerId,
                        brush = directPreset?.let(::brushFor) ?: currentBrush,
                        motionEventToWorldTransform = viewport.viewToDocumentMatrix(),
                        strokeToWorldTransform = Matrix(),
                    )
                if (pressureEraser.enabled) {
                    schedulePressureEraser(lastPressure, event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime)
                }
            }

            DrawingToolKind.ERASER -> {
                mainHandler.removeCallbacks(activatePressureEraser)
                pressureEraser.cancelCandidate()
                eraseAt(lastPressurePoint, selectedEraserRadius())
            }

            DrawingToolKind.LASSO -> {
                tagLayer.clearSelection()
                selectionOverlay.invalidate()
                dryInkView.beginLasso(lastPressurePoint)
            }
            null -> Unit
        }
    }

    private fun continueStylusAction(event: MotionEvent) {
        val pressureIndex = pressurePointerId?.let(event::findPointerIndex) ?: -1
        if (pressureIndex < 0) return
        lastPressure = event.getPressure(pressureIndex)
        lastPressurePoint = viewport.viewToDocument(event.getX(pressureIndex), event.getY(pressureIndex))

        when (activeDirectTool) {
            DrawingToolKind.ERASER -> eraseAt(lastPressurePoint, selectedEraserRadius())
            DrawingToolKind.LASSO -> dryInkView.extendLasso(lastPressurePoint)
            DrawingToolKind.PEN,
            DrawingToolKind.HIGHLIGHTER -> {
                if (pressureEraser.isErasing) {
                    eraseAt(lastPressurePoint, dp(18) / viewport.scale)
                } else {
                    val prediction = if (predictionEnabled) predictor.predict() else null
                    for ((pointerId, strokeId) in activeStrokes) {
                        if (event.findPointerIndex(pointerId) >= 0) wetInkView.addToStroke(event, pointerId, strokeId, prediction)
                    }
                    prediction?.recycle()
                    if (pressureEraser.enabled) {
                        schedulePressureEraser(lastPressure, event.getX(pressureIndex), event.getY(pressureIndex), event.eventTime)
                    }
                }
            }

            null -> Unit
        }
    }

    private fun finishStylusAction(event: MotionEvent) {
        val pointerId = event.getPointerId(event.actionIndex)
        if (pointerId != pressurePointerId) return
        when (activeDirectTool) {
            DrawingToolKind.PEN,
            DrawingToolKind.HIGHLIGHTER -> {
                val strokeId = activeStrokes.remove(pointerId)
                val wasErasing = pressureEraser.isErasing
                if (!wasErasing && strokeId != null) wetInkView.finishStroke(event, pointerId, strokeId)
                mainHandler.removeCallbacks(activatePressureEraser)
                pressureEraser.onStylusLift(completedInk = !wasErasing && strokeId != null)
                dryInkView.setEraserCursor(null)
            }

            DrawingToolKind.LASSO -> {
                val polygon = dryInkView.finishLasso()
                tagLayer.selectInPolygon(polygon)
                selectionOverlay.invalidate()
                renderToolDetails()
            }

            DrawingToolKind.ERASER -> {
                pressureEraser.cancelCandidate()
                dryInkView.setEraserCursor(null)
            }

            null -> Unit
        }
        pressurePointerId = null
        activeDirectTool = null
        syncPressureEraserWithTool()
    }

    private fun cancelStylusAction() {
        mainHandler.removeCallbacks(activatePressureEraser)
        pressureEraser.cancelCandidate()
        pressurePointerId = null
        activeDirectTool = null
        dryInkView.setEraserCursor(null)
        clearContentSelection()
        wetInkView.cancelUnfinishedStrokes()
        activeStrokes.clear()
        syncPressureEraserWithTool()
    }

    private fun selectTool(id: String) {
        if (!toolShelf.select(id)) return
        if (toolShelf.selected.kind == DrawingToolKind.PEN) lastPenToolId = toolShelf.selected.id
        tagLayer.cancelPlacement()
        restoreTextModeAfterTagPlacement = false
        setTextMode(false, rerender = false)
        singleFingerPanEnabled = false
        transformGestureActive = false
        toolDetailPopup?.dismiss()
        toolDetailPopup = null
        mainHandler.removeCallbacks(activatePressureEraser)
        pressureEraser.cancelCandidate()
        dryInkView.setEraserCursor(null)
        if (toolShelf.selected.kind != DrawingToolKind.LASSO) clearContentSelection()
        rebuildBrush()
        updateSelectionInteraction()
        renderToolShelf()
        renderToolDetails()
    }

    private fun setTextMode(enabled: Boolean, rerender: Boolean = true) {
        if (textModeEnabled == enabled && ::textInputLayer.isInitialized) {
            textInputLayer.setTextMode(enabled)
            updateSelectionInteraction()
            if (rerender) renderToolShelf()
            return
        }
        textModeEnabled = enabled
        if (enabled) {
            tagLayer.cancelPlacement()
            singleFingerPanEnabled = false
            transformGestureActive = false
            toolDetailPopup?.dismiss()
            toolDetailPopup = null
            mainHandler.removeCallbacks(activatePressureEraser)
            pressureEraser.cancelCandidate()
            dryInkView.setEraserCursor(null)
            clearContentSelection()
        }
        if (::textInputLayer.isInitialized) textInputLayer.setTextMode(enabled)
        updateSelectionInteraction()
        if (rerender) renderToolShelf()
    }

    private fun cursorModePenPreset(): ToolPreset? =
        toolShelf.tools.firstOrNull { it.id == lastPenToolId && it.kind == DrawingToolKind.PEN }
            ?: toolShelf.tools.firstOrNull { it.kind == DrawingToolKind.PEN }?.also { lastPenToolId = it.id }

    private fun rebuildBrush() {
        if (toolShelf.selected.kind == DrawingToolKind.PEN || toolShelf.selected.kind == DrawingToolKind.HIGHLIGHTER) {
            currentBrush = brushFor(toolShelf.selected)
        }
        syncPressureEraserWithTool()
    }

    private fun brushFor(tool: ToolPreset): Brush =
        when (tool.kind) {
            DrawingToolKind.HIGHLIGHTER ->
                BrushProfiles.createHighlighter(
                    sizePx = tool.widthMm * INTERNAL_UNITS_PER_MM,
                    colorArgb = displayColor(tool.color),
                    opacityPercent = tool.opacityPercent,
                )
            else ->
                BrushProfiles.create(
                    sizePx = tool.widthMm * INTERNAL_UNITS_PER_MM,
                    pressureCorrect = tool.pressurePercent / 100f,
                    speedCorrect = tool.inkFlowPercent / 100f,
                    colorArgb = displayColor(tool.color),
                )
        }

    private fun syncPressureEraserWithTool() {
        pressureEraser.enabled = toolShelf.selected.kind == DrawingToolKind.PEN && toolShelf.selected.pressureEraseEnabled
        if (!pressureEraser.enabled) {
            mainHandler.removeCallbacks(activatePressureEraser)
            pressureEraser.cancelCandidate()
        }
    }

    private fun isStylus(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    private fun hasStylusPointer(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { isStylus(event.getToolType(it)) }

    private fun handleFingerGesture(event: MotionEvent): Boolean {
        if (hasStylusPointer(event) || activeStrokes.isNotEmpty()) return false
        val fingerIndices = (0 until event.pointerCount).filter { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
        if (fingerIndices.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (singleFingerPanEnabled && fingerIndices.size == 1) beginSingleFingerPan(event, fingerIndices[0])
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (fingerIndices.size >= 2) beginTransformGesture(event, fingerIndices)
            MotionEvent.ACTION_MOVE -> {
                if (fingerIndices.size >= 2) {
                    val (focusX, focusY, span) = gestureGeometry(event, fingerIndices)
                    if (!transformGestureActive || lastGestureSpan <= 0f) {
                        beginTransformGesture(event, fingerIndices)
                    } else {
                        applyViewportGesture(focusX, focusY, span / lastGestureSpan, span)
                    }
                } else if (singleFingerPanEnabled) {
                    val index = fingerIndices[0]
                    val focusX = event.getX(index)
                    val focusY = event.getY(index)
                    if (!transformGestureActive) {
                        beginSingleFingerPan(event, index)
                    } else {
                        applyViewportGesture(focusX, focusY, 1f, 0f)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = fingerIndices.filter { it != event.actionIndex }
                when {
                    remaining.size >= 2 -> beginTransformGesture(event, remaining)
                    singleFingerPanEnabled && remaining.size == 1 -> beginSingleFingerPan(event, remaining[0])
                    else -> transformGestureActive = false
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> transformGestureActive = false
        }
        return true
    }

    private fun beginSingleFingerPan(event: MotionEvent, fingerIndex: Int) {
        transformGestureActive = true
        lastGestureFocusX = event.getX(fingerIndex)
        lastGestureFocusY = event.getY(fingerIndex)
        lastGestureSpan = 0f
    }

    private fun applyViewportGesture(focusX: Float, focusY: Float, scaleFactor: Float, nextSpan: Float) {
        viewport.applyGesture(
            previousFocusX = lastGestureFocusX,
            previousFocusY = lastGestureFocusY,
            focusX = focusX,
            focusY = focusY,
            requestedScaleFactor = scaleFactor,
        )
        lastGestureFocusX = focusX
        lastGestureFocusY = focusY
        lastGestureSpan = nextSpan
        dryInkView.setViewport(viewport.documentToViewMatrix())
        tagLayer.invalidate()
        selectionOverlay.invalidate()
    }

    private fun beginTransformGesture(event: MotionEvent, fingerIndices: List<Int>) {
        val (focusX, focusY, span) = gestureGeometry(event, fingerIndices)
        transformGestureActive = true
        lastGestureFocusX = focusX
        lastGestureFocusY = focusY
        lastGestureSpan = span
    }

    private fun gestureGeometry(event: MotionEvent, fingerIndices: List<Int>): Triple<Float, Float, Float> {
        val first = fingerIndices[0]
        val second = fingerIndices[1]
        val deltaX = event.getX(second) - event.getX(first)
        val deltaY = event.getY(second) - event.getY(first)
        return Triple(
            (event.getX(first) + event.getX(second)) * 0.5f,
            (event.getY(first) + event.getY(second)) * 0.5f,
            sqrt(deltaX * deltaX + deltaY * deltaY).coerceAtLeast(1f),
        )
    }

    private fun schedulePressureEraser(pressure: Float, viewX: Float, viewY: Float, eventTime: Long) {
        mainHandler.removeCallbacks(activatePressureEraser)
        val remaining = pressureEraser.updateSample(pressure, viewX, viewY, eventTime) ?: return
        mainHandler.postDelayed(activatePressureEraser, remaining.coerceAtLeast(1L))
    }

    private fun selectedEraserRadius(): Float {
        val preset = toolShelf.selected
        val radiusMm = if (preset.kind == DrawingToolKind.ERASER) preset.eraserRadiusMm else 4.5f
        return radiusMm * INTERNAL_UNITS_PER_MM
    }

    private fun eraseAt(point: PointF, radius: Float) {
        dryInkView.setEraserCursor(point, radius)
        val removed =
            dryInkView.eraseAtDocument(point.x, point.y, radius) +
                tagLayer.eraseAtDocument(point.x, point.y, radius)
        if (removed > 0) {
            selectionOverlay.invalidate()
            if (toolShelf.selected.kind == DrawingToolKind.LASSO) renderToolDetails()
        }
    }

    private fun selectedContentCount(): Int = dryInkView.selectedCount() + tagLayer.selectedCount()

    private fun clearContentSelection() {
        dryInkView.clearSelection()
        if (::tagLayer.isInitialized) tagLayer.clearSelection()
        if (::selectionOverlay.isInitialized) selectionOverlay.invalidate()
    }

    private fun deleteContentSelection(): Int {
        val removed = dryInkView.deleteSelection() + tagLayer.deleteSelection()
        selectionOverlay.invalidate()
        return removed
    }

    private fun combinedSelectionBounds(): RectF? {
        val strokeBounds = dryInkView.selectedBounds()
        val tagBounds = tagLayer.selectedBounds()
        if (strokeBounds == null) return tagBounds?.let(::RectF)
        if (tagBounds == null) return RectF(strokeBounds)
        return RectF(strokeBounds).apply { union(tagBounds) }
    }

    private fun updateSelectionInteraction() {
        if (!::selectionOverlay.isInitialized) return
        val lassoActive = !textModeEnabled && toolShelf.selected.kind == DrawingToolKind.LASSO
        selectionOverlay.selectionEnabled = lassoActive
        tagLayer.selectionModeEnabled = lassoActive
        selectionOverlay.invalidate()
    }

    private fun toggleSectionPane() {
        sectionPaneVisible = !sectionPaneVisible
        applyChromeVisibility()
    }

    private fun togglePagePane() {
        pagePaneVisible = !pagePaneVisible
        applyChromeVisibility()
    }

    private fun applyChromeVisibility() {
        if (!::ribbonTabs.isInitialized) return
        if (!fullPageMode) drawingToolbarVisible = true
        ribbonTabs.visibility = if (fullPageMode) View.GONE else View.VISIBLE
        drawingRibbon.visibility = if (fullPageMode) View.GONE else View.VISIBLE
        floatingToolbox.visibility = if (fullPageMode && drawingToolbarVisible) View.VISIBLE else View.GONE
        toolbarToggle.visibility = if (fullPageMode) View.VISIBLE else View.GONE
        navigationRail.visibility = if (fullPageMode) View.GONE else View.VISIBLE
        navigationPane.visibility = if (!fullPageMode && sectionPaneVisible) View.VISIBLE else View.GONE
        pagePane.visibility = if (!fullPageMode && pagePaneVisible) View.VISIBLE else View.GONE
        updateToggleVisual(toolbarToggle, drawingToolbarVisible)
        updateToggleVisual(sectionPaneToggle, sectionPaneVisible)
        updateToggleVisual(pagePaneToggle, pagePaneVisible)
        fullPageToggleIcon.setImageDrawable(
            fluentIcon(if (fullPageMode) "full_screen_minimize" else "full_screen_maximize"),
        )
        fullPageToggleIcon.imageTintList = ColorStateList.valueOf(TEXT_PRIMARY)
        val fullPageDescription = if (fullPageMode) "退出整页视图" else "进入整页视图"
        fullPageToggle.contentDescription = fullPageDescription
        fullPageToggle.tooltipText = fullPageDescription
    }

    private fun showAddToolMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("添加钢笔").setOnMenuItemClickListener { addTool(DrawingToolKind.PEN); true }
            menu.add("添加荧光笔").setOnMenuItemClickListener { addTool(DrawingToolKind.HIGHLIGHTER); true }
            menu.add("添加橡皮").setOnMenuItemClickListener { addTool(DrawingToolKind.ERASER); true }
            menu.add("添加框选").setOnMenuItemClickListener { addTool(DrawingToolKind.LASSO); true }
            show()
        }
    }

    private fun showNavigationAddMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("新建笔记本").setOnMenuItemClickListener {
                notebookLibrary.addNotebook()
                clearContentSelection()
                renderWorkspace()
                true
            }
            menu.add("新建分区").setOnMenuItemClickListener {
                workspace.addSectionAtFocus()
                renderWorkspace()
                true
            }
            show()
        }
    }

    private fun addTool(kind: DrawingToolKind) {
        val added = toolShelf.add(kind)
        if (kind == DrawingToolKind.PEN) lastPenToolId = added.id
        setTextMode(false, rerender = false)
        updateSelectionInteraction()
        rebuildBrush()
        renderToolShelf()
        renderToolDetails()
    }

    private fun confirmClearPage() {
        AlertDialog.Builder(this)
            .setTitle("清空当前页面？")
            .setMessage("本原型的笔迹、文本和标记只保存在当前会话，清空后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                dryInkView.clear()
                textInputLayer.clearAll()
                tagLayer.clear()
                renderToolDetails()
            }
            .show()
    }

    private fun ribbonTab(label: String, selected: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            background =
                if (selected) bottomBorderDrawable(RIBBON_BACKGROUND, ACCENT_PURPLE)
                else ColorStateList.valueOf(Color.TRANSPARENT).let { rounded(Color.TRANSPARENT, 0f, Color.TRANSPARENT) }
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(58), MATCH)
        }

    private fun toolButton(tool: ToolPreset): View {
        val selected = !singleFingerPanEnabled && !textModeEnabled && tool.id == toolShelf.selectedId
        return FrameLayout(this).apply {
            background =
                if (selected) rounded(SELECTED_LAVENDER, dp(2).toFloat(), BORDER_STRONG)
                else rounded(Color.TRANSPARENT, dp(2).toFloat(), Color.TRANSPARENT)
            val iconName =
                when (tool.kind) {
                    DrawingToolKind.PEN -> "pen"
                    DrawingToolKind.HIGHLIGHTER -> "highlight"
                    DrawingToolKind.ERASER -> "eraser"
                    DrawingToolKind.LASSO -> "lasso"
                }
            addView(
                ImageView(this@MainActivity).apply {
                    setImageDrawable(fluentIcon(iconName, if (tool.kind == DrawingToolKind.LASSO) "select_object" else "edit"))
                    imageTintList =
                        ColorStateList.valueOf(
                            when (tool.kind) {
                                DrawingToolKind.PEN,
                                DrawingToolKind.HIGHLIGHTER -> displayColor(tool.color)
                                else -> if (selected) ACCENT_PURPLE else TEXT_PRIMARY
                            },
                        )
                },
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
            )
            if (selected) {
                addView(
                    TextView(this@MainActivity).apply {
                        text = "⌄"
                        gravity = Gravity.CENTER
                        setTextColor(ACCENT_PURPLE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    },
                    FrameLayout.LayoutParams(dp(14), dp(14), Gravity.END or Gravity.BOTTOM).apply {
                        rightMargin = dp(2)
                        bottomMargin = dp(1)
                    },
                )
            }
            tag = tool.id
            setOnClickListener { clicked ->
                if (selected) {
                    showToolDetails(clicked)
                } else {
                    selectTool(tool.id)
                }
            }
            contentDescription = tool.displayName
            tooltipText = "点击选择；再次点击打开参数"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(42)).apply { marginEnd = dp(2) }
        }
    }

    private fun commandButton(icon: String, label: String, selected: Boolean, action: (View) -> Unit): View =
        FrameLayout(this).apply {
            background = toggleBackground(selected)
            val iconView =
                ImageView(this@MainActivity).apply {
                    setImageDrawable(fluentIcon(icon))
                    imageTintList = ColorStateList.valueOf(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
                    if (icon == "hand_left") translationX = dp(1).toFloat()
                }
            addView(iconView, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { action(this) }
            contentDescription = label
            tooltipText = label
        }

    private fun railToggleButton(icon: String, label: String, action: () -> Unit): View =
        FrameLayout(this).apply {
            addView(
                ImageView(this@MainActivity).apply {
                    setImageDrawable(fluentIcon(icon))
                    imageTintList = ColorStateList.valueOf(TEXT_PRIMARY)
                },
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER),
            )
            setOnClickListener { action() }
            contentDescription = label
            tooltipText = label
            layoutParams =
                LinearLayout.LayoutParams(dp(28), dp(34)).apply {
                    topMargin = dp(2)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
        }

    private fun canvasActionButton(icon: String, label: String, selected: Boolean, action: () -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = toggleBackground(selected, opaqueWhenIdle = true)
            addView(
                ImageView(this@MainActivity).apply {
                    setImageDrawable(fluentIcon(icon))
                    imageTintList = ColorStateList.valueOf(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
                },
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER),
            )
            setOnClickListener { action() }
            contentDescription = label
            tooltipText = label
        }

    private fun updateToggleVisual(view: View, selected: Boolean) {
        view.background = toggleBackground(selected, opaqueWhenIdle = view === toolbarToggle)
        (view as? ViewGroup)?.getChildAt(0)?.let { icon ->
            if (icon is ImageView) icon.imageTintList = ColorStateList.valueOf(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
        }
    }

    private fun toggleBackground(selected: Boolean, opaqueWhenIdle: Boolean = false): Drawable =
        when {
            selected -> rounded(SELECTED_LAVENDER, dp(2).toFloat(), BORDER_STRONG)
            opaqueWhenIdle -> rounded(TOOLBAR_BACKGROUND, dp(2).toFloat(), BORDER_STRONG)
            else -> rounded(Color.TRANSPARENT, dp(2).toFloat(), Color.TRANSPARENT)
        }

    private fun parameterControl(
        label: String,
        initial: Int,
        max: Int,
        value: (Int) -> String,
        onChanged: (Int) -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(7), 0)
            val text =
                TextView(this@MainActivity).apply {
                    this.text = "$label ${value(initial)}"
                    setTextColor(TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    gravity = Gravity.CENTER_VERTICAL
                }
            addView(text, LinearLayout.LayoutParams(dp(84), dp(42)))
            addView(
                SeekBar(this@MainActivity).apply {
                    this.max = max
                    progress = initial
                    progressTintList = ColorStateList.valueOf(ACCENT_PURPLE)
                    thumbTintList = ColorStateList.valueOf(ACCENT_PURPLE)
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                text.text = "$label ${value(progress)}"
                                if (fromUser) onChanged(progress)
                            }
                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                        },
                    )
                },
                LinearLayout.LayoutParams(dp(104), dp(42)),
            )
        }

    private fun colorButton(color: InkPalette, selected: Boolean, action: () -> Unit): View =
        View(this).apply {
            contentDescription = "${color.label}色"
            background = oval(color.argb, if (selected) ACCENT_PURPLE else BORDER_STRONG, if (selected) 3 else 1)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(7)
                gravity = Gravity.CENTER_VERTICAL
            }
        }

    private fun detailChip(label: String, selected: Boolean, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(11), 0, dp(11), 0)
            background =
                if (selected) rounded(SELECTED_LAVENDER, dp(2).toFloat(), ACCENT_PURPLE)
                else rounded(TOOLBAR_BACKGROUND, dp(2).toFloat(), BORDER_STRONG)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(WRAP, dp(34)).apply {
                marginEnd = dp(7)
                gravity = Gravity.CENTER_VERTICAL
            }
        }

    private fun detailHint(label: String): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(WRAP, MATCH)
        }

    private fun sectionCaption(label: String): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), 0, dp(10), 0)
        }

    private fun titleIconButton(icon: String, description: String, action: (View) -> Unit): TextView =
        TextView(this).apply {
            contentDescription = description
            gravity = Gravity.CENTER
            setCompoundDrawablesRelativeWithIntrinsicBounds(fluentIcon(icon), null, null, null)
            compoundDrawableTintList = ColorStateList.valueOf(if (description == "导航") Color.WHITE else TEXT_PRIMARY)
            background = rounded(Color.TRANSPARENT, 0f, Color.TRANSPARENT)
            setOnClickListener { action(this) }
            tooltipText = description
        }

    private fun verticalDivider(): View =
        View(this).apply {
            setBackgroundColor(BORDER_COLOR)
            layoutParams = LinearLayout.LayoutParams(1, dp(26)).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
                gravity = Gravity.CENTER_VERTICAL
            }
        }

    private fun horizontalDivider(): View =
        View(this).apply { setBackgroundColor(BORDER_COLOR) }

    private fun showShell(name: String) {
        Toast.makeText(this, "${name}已保留入口，后续接入", Toast.LENGTH_SHORT).show()
    }

    private fun fluentIcon(primary: String, fallback: String? = null): Drawable? {
        val names = buildList {
            add(primary)
            fallback?.let(::add)
        }
        for (name in names) {
            for (size in listOf(24, 20, 16)) {
                val resourceName = "ic_fluent_${name}_${size}_regular"
                val id = resources.getIdentifier(resourceName, "drawable", packageName)
                if (id != 0) getDrawable(id)?.mutate()?.let { return it }
            }
        }
        return null
    }

    private fun sectionAccent(depth: Int): Int =
        listOf(0xFF7719AA.toInt(), 0xFF185ABD.toInt(), 0xFFC239B3.toInt(), 0xFFCA5010.toInt())[depth % 4]

    /** OneNote dark mode shows logical black ink as light ink without changing its saved color. */
    private fun displayColor(color: InkPalette): Int =
        if (color == InkPalette.BLACK) Color.rgb(239, 239, 239) else color.argb

    private fun rounded(fill: Int, radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            setStroke(dp(1), stroke)
        }

    private fun bordered(fill: Int, stroke: Int, radius: Float): GradientDrawable = rounded(fill, radius, stroke)

    private fun oval(fill: Int, stroke: Int, strokeWidth: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(strokeWidth), stroke)
        }

    private fun bottomBorderDrawable(fill: Int, accent: Int): Drawable =
        android.graphics.drawable.LayerDrawable(
            arrayOf(
                GradientDrawable().apply { setColor(fill) },
                GradientDrawable().apply { setColor(accent) },
            ),
        ).apply {
            setLayerInset(1, 0, dp(31), 0, 0)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val INTERNAL_UNITS_PER_MM = 12.16225f
        private const val OPTIONS_PREFERENCES = "workspace-options"
        private const val FULL_PAGE_TOOLBAR_KEY = "full-page-toolbar"
        private const val TOOLBAR_ID_SEPARATOR = "\u001F"
        private val TITLE_PURPLE = Color.rgb(20, 20, 20)
        private val RIBBON_BACKGROUND = Color.rgb(17, 17, 17)
        private val ACCENT_PURPLE = Color.rgb(218, 178, 233)
        private val SELECTED_LAVENDER = Color.rgb(55, 64, 72)
        private val TOOLBAR_BACKGROUND = Color.rgb(35, 35, 35)
        private val NAV_BACKGROUND = Color.rgb(15, 15, 15)
        private val PAGE_LIST_BACKGROUND = Color.rgb(42, 42, 42)
        private val PAPER_COLOR = Color.rgb(27, 27, 27)
        private val BORDER_COLOR = Color.rgb(61, 61, 61)
        private val BORDER_STRONG = Color.rgb(87, 87, 87)
        private val TEXT_PRIMARY = Color.rgb(244, 244, 244)
        private val TEXT_SECONDARY = Color.rgb(185, 185, 185)
    }
}
