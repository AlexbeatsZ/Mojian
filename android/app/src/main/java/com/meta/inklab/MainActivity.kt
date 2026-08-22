package com.meta.inklab

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

class MainActivity : Activity() {
    private val activeStrokes = mutableMapOf<Int, InProgressStrokeId>()
    private val viewport = ViewportState()
    private val pressureEraser = PressureEraserController()
    private val shortcutDispatcher = XiaomiStylusShortcutDispatcher()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val toolShelf = ToolShelf()
    private val notebookLibrary = NotebookLibrary.sample()
    private val workspace: NotebookWorkspace
        get() = notebookLibrary.activeNotebook()

    private lateinit var wetInkView: InProgressStrokesView
    private lateinit var dryInkView: DryInkView
    private lateinit var predictor: MotionEventPredictor
    private lateinit var currentBrush: Brush
    private lateinit var toolShelfRow: LinearLayout
    private lateinit var toolDetailRow: LinearLayout
    private var toolDetailPopup: PopupWindow? = null
    private var backstageDialog: Dialog? = null
    private lateinit var sectionList: LinearLayout
    private lateinit var pageList: LinearLayout
    private lateinit var navigationPane: View
    private lateinit var pagePane: View

    private var predictionEnabled = true
    private var transformGestureActive = false
    private var lastGestureFocusX = 0f
    private var lastGestureFocusY = 0f
    private var lastGestureSpan = 0f
    private var pressurePointerId: Int? = null
    private var lastPressure = 0f
    private var lastPressurePoint = PointF()
    private var activeDirectTool: DrawingToolKind? = null
    private var singleFingerPanEnabled = false
    private var sectionPaneVisible = true
    private var pagePaneVisible = true

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
        dryInkView.clearSelection()
        wetInkView.cancelUnfinishedStrokes()
        activeStrokes.clear()
        super.onPause()
    }

    private fun buildInterface(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(PAPER_COLOR)
            }
        root.setOnApplyWindowInsetsListener { view, insets ->
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

        root.addView(buildRibbonTabs(), LinearLayout.LayoutParams(MATCH, dp(34)))
        root.addView(buildDrawingRibbon(), LinearLayout.LayoutParams(MATCH, dp(50)))

        val workArea =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(PAPER_COLOR)
            }
        navigationPane = buildNavigationPane()
        pagePane = buildPagePane()
        workArea.addView(buildNavigationRail(), LinearLayout.LayoutParams(dp(36), MATCH))
        workArea.addView(navigationPane, LinearLayout.LayoutParams(dp(196), MATCH))
        workArea.addView(pagePane, LinearLayout.LayoutParams(dp(196), MATCH))
        workArea.addView(buildPaper(), LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(workArea, LinearLayout.LayoutParams(MATCH, 0, 1f))

        return root
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
                        dryInkView.clearSelection()
                        renderWorkspace()
                        dialog.dismiss()
                    }
                },
                LinearLayout.LayoutParams(dp(72), dp(36)),
            )
        }

    private fun showOptionsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.rgb(37, 37, 37))

                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), dp(14), dp(8), dp(14))
                        setBackgroundColor(NAV_BACKGROUND)
                        addView(backstageNavItem("常规", true) {}, LinearLayout.LayoutParams(MATCH, dp(40)))
                        for (label in listOf("显示", "同步", "保存和备份", "语言", "高级")) {
                            addView(backstageNavItem(label, false) { showShell("$label 选项") }, LinearLayout.LayoutParams(MATCH, dp(40)))
                        }
                    },
                    LinearLayout.LayoutParams(dp(132), MATCH),
                )

                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(24), dp(20), dp(24), dp(16))
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "墨笺 常规选项"
                                setTextColor(TEXT_PRIMARY)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            },
                            LinearLayout.LayoutParams(MATCH, dp(44)),
                        )
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "绘图与页面"
                                setTextColor(TEXT_PRIMARY)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            },
                            LinearLayout.LayoutParams(MATCH, dp(34)),
                        )
                        addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)))

                        val predictionValue = settingsValueButton(if (predictionEnabled) "开启" else "关闭")
                        predictionValue.setOnClickListener {
                            predictionEnabled = !predictionEnabled
                            predictionValue.text = if (predictionEnabled) "开启" else "关闭"
                        }
                        addView(
                            settingsRow("笔迹预测", "使用预测触点降低快速书写时的视觉延迟。", predictionValue),
                            LinearLayout.LayoutParams(MATCH, dp(72)),
                        )

                        val pageModeValue =
                            settingsValueButton(if (workspace.pagePaneMode == PagePaneMode.CURRENT_SECTION) "本层" else "递归")
                        pageModeValue.setOnClickListener {
                            val mode =
                                if (workspace.pagePaneMode == PagePaneMode.CURRENT_SECTION) PagePaneMode.RECURSIVE
                                else PagePaneMode.CURRENT_SECTION
                            notebookLibrary.setPagePaneMode(mode)
                            pageModeValue.text = if (mode == PagePaneMode.CURRENT_SECTION) "本层" else "递归"
                            renderPages()
                        }
                        addView(
                            settingsRow("页面栏显示范围", "本层只显示当前分区页面；递归还显示所有子分区及其页面。", pageModeValue),
                            LinearLayout.LayoutParams(MATCH, dp(72)),
                        )
                        addView(View(this@MainActivity), LinearLayout.LayoutParams(MATCH, 0, 1f))
                        addView(
                            TextView(this@MainActivity).apply {
                                text = "关闭"
                                gravity = Gravity.CENTER
                                setTextColor(TEXT_PRIMARY)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                background = rounded(Color.rgb(48, 48, 48), dp(2).toFloat(), TEXT_SECONDARY)
                                setOnClickListener { dialog.dismiss() }
                            },
                            LinearLayout.LayoutParams(dp(80), dp(36)).apply { gravity = Gravity.END },
                        )
                    },
                    LinearLayout.LayoutParams(0, MATCH, 1f),
                )
            }
        dialog.setContentView(content)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(650), dp(470))
        }
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
            addView(ribbonTab("文件", false) { showBackstage() })
            addView(ribbonTab("主页", false) { showShell("主页工具") })
            addView(ribbonTab("插入", false) { showShell("插入工具") })
            addView(ribbonTab("绘图", true) { })
            addView(ribbonTab("视图", false) { showShell("视图工具") })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(
                titleIconButton("search", "搜索") { showShell("全文搜索") },
                LinearLayout.LayoutParams(dp(38), dp(34)),
            )
        }

    private fun buildNavigationRail(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setBackgroundColor(TITLE_PURPLE)
            addView(
                titleIconButton("navigation", "折叠或展开分区栏") { toggleSectionPane() },
                LinearLayout.LayoutParams(dp(36), dp(40)),
            )
            addView(
                titleIconButton("panel_left", "折叠或展开页面栏") { togglePagePane() },
                LinearLayout.LayoutParams(dp(36), dp(40)),
            )
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

    private fun buildNavigationPane(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAV_BACKGROUND)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(10), dp(8))
                    addView(
                        ImageView(this@MainActivity).apply {
                            setImageDrawable(fluentIcon("notebook", "book"))
                            imageTintList = ColorStateList.valueOf(ACCENT_PURPLE)
                        },
                        LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(9) },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "笔记本"
                            setTextColor(TEXT_PRIMARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            gravity = Gravity.CENTER_VERTICAL
                        },
                        LinearLayout.LayoutParams(0, dp(34), 1f),
                    )
                    addView(
                        titleIconButton("add", "按当前分区焦点新建分区") {
                            workspace.addSectionAtFocus()
                            renderWorkspace()
                        },
                        LinearLayout.LayoutParams(dp(38), dp(38)),
                    )
                },
                LinearLayout.LayoutParams(MATCH, dp(52)),
            )
            addView(sectionCaption("分区"), LinearLayout.LayoutParams(MATCH, dp(28)))

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
                    setPadding(dp(12), 0, dp(6), 0)
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "页面"
                            setTextColor(TEXT_PRIMARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            gravity = Gravity.CENTER_VERTICAL
                        },
                        LinearLayout.LayoutParams(0, MATCH, 1f),
                    )
                    addView(
                        titleIconButton("add", "新建页面") {
                            workspace.addPage()
                            renderPages()
                        },
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
        dryInkView.setViewport(viewport.documentToViewMatrix())
        paper.addView(dryInkView, FrameLayout.LayoutParams(MATCH, MATCH))
        paper.addView(wetInkView, FrameLayout.LayoutParams(MATCH, MATCH))
        return paper
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
                        dryInkView.clearSelection()
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
                            dryInkView.clearSelection()
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
        pageList.removeAllViews()
        val rows = workspace.pagePaneRows()
        if (rows.isEmpty()) {
            pageList.addView(
                TextView(this).apply {
                    text = "这里还没有页面\n点击右上角 + 新建"
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
        toolShelfRow.removeAllViews()
        toolShelfRow.addView(
            commandButton("arrow_undo", "撤销", selected = false) {
                dryInkView.clearSelection()
                dryInkView.undo()
            },
            LinearLayout.LayoutParams(dp(40), dp(42)),
        )
        toolShelfRow.addView(verticalDivider())
        toolShelf.tools.filter { it.kind == DrawingToolKind.LASSO }.forEach { toolShelfRow.addView(toolButton(it)) }
        toolShelfRow.addView(verticalDivider())
        toolShelf.tools.filter { it.kind == DrawingToolKind.ERASER }.forEach { toolShelfRow.addView(toolButton(it)) }
        toolShelf.tools.filter { it.kind == DrawingToolKind.HIGHLIGHTER }.forEach { toolShelfRow.addView(toolButton(it)) }
        toolShelf.tools.filter { it.kind == DrawingToolKind.PEN }.forEach { toolShelfRow.addView(toolButton(it)) }
        toolShelfRow.addView(
            commandButton("add", "添加", selected = false) { anchor -> showAddToolMenu(anchor) },
            LinearLayout.LayoutParams(dp(40), dp(42)).apply { marginEnd = dp(3) },
        )
        toolShelfRow.addView(verticalDivider())
        toolShelfRow.addView(
            commandButton("hand_left", "单指平移", selected = singleFingerPanEnabled) {
                singleFingerPanEnabled = !singleFingerPanEnabled
                transformGestureActive = false
                toolDetailPopup?.dismiss()
                toolDetailPopup = null
                renderToolShelf()
            },
            LinearLayout.LayoutParams(dp(40), dp(42)),
        )
        toolShelfRow.addView(verticalDivider())
        toolShelfRow.addView(
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
                addToolDetailLine(detailHint("闭合套索后选择整条笔迹"))
                addToolDetailLine(
                    detailChip("删除所选 ${dryInkView.selectedCount()}", dryInkView.selectedCount() > 0) {
                        val removed = dryInkView.deleteSelection()
                        if (removed == 0) Toast.makeText(this, "请先圈选笔迹", Toast.LENGTH_SHORT).show()
                        renderToolDetails()
                    },
                )
                addToolDetailLine(detailHint("移动与缩放稍后接入"))
            }
        }

        toolDetailRow.addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(4) })
        addToolDetailLine(
            detailChip("删除工具", false) {
                if (!toolShelf.deleteSelected()) {
                    Toast.makeText(this, "至少保留一个工具", Toast.LENGTH_SHORT).show()
                    return@detailChip
                }
                dryInkView.clearSelection()
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
        activeDirectTool =
            if (event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER) DrawingToolKind.ERASER
            else toolShelf.selected.kind

        when (activeDirectTool) {
            DrawingToolKind.PEN,
            DrawingToolKind.HIGHLIGHTER -> {
                activeStrokes[pointerId] =
                    wetInkView.startStroke(
                        event = event,
                        pointerId = pointerId,
                        brush = currentBrush,
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

            DrawingToolKind.LASSO -> dryInkView.beginLasso(lastPressurePoint)
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
            }

            DrawingToolKind.LASSO -> {
                dryInkView.finishLasso()
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
    }

    private fun cancelStylusAction() {
        mainHandler.removeCallbacks(activatePressureEraser)
        pressureEraser.cancelCandidate()
        pressurePointerId = null
        activeDirectTool = null
        dryInkView.setEraserCursor(null)
        dryInkView.clearSelection()
        wetInkView.cancelUnfinishedStrokes()
        activeStrokes.clear()
    }

    private fun selectTool(id: String) {
        if (!toolShelf.select(id)) return
        singleFingerPanEnabled = false
        transformGestureActive = false
        toolDetailPopup?.dismiss()
        toolDetailPopup = null
        mainHandler.removeCallbacks(activatePressureEraser)
        pressureEraser.cancelCandidate()
        dryInkView.setEraserCursor(null)
        if (toolShelf.selected.kind != DrawingToolKind.LASSO) dryInkView.clearSelection()
        rebuildBrush()
        renderToolShelf()
        renderToolDetails()
    }

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
        dryInkView.eraseAtDocument(point.x, point.y, radius)
    }

    private fun toggleSectionPane() {
        sectionPaneVisible = !sectionPaneVisible
        navigationPane.visibility = if (sectionPaneVisible) View.VISIBLE else View.GONE
    }

    private fun togglePagePane() {
        pagePaneVisible = !pagePaneVisible
        pagePane.visibility = if (pagePaneVisible) View.VISIBLE else View.GONE
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

    private fun addTool(kind: DrawingToolKind) {
        toolShelf.add(kind)
        rebuildBrush()
        renderToolShelf()
        renderToolDetails()
    }

    private fun confirmClearPage() {
        AlertDialog.Builder(this)
            .setTitle("清空当前页面？")
            .setMessage("本原型的笔迹只保存在当前会话，清空后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                dryInkView.clear()
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
        val selected = !singleFingerPanEnabled && tool.id == toolShelf.selectedId
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

    private fun commandButton(icon: String, label: String, selected: Boolean, action: (View) -> Unit): TextView =
        TextView(this).apply {
            text = ""
            gravity = Gravity.CENTER
            setTextColor(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setCompoundDrawablesRelativeWithIntrinsicBounds(fluentIcon(icon), null, null, null)
            compoundDrawableTintList = ColorStateList.valueOf(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
            background =
                if (selected) rounded(SELECTED_LAVENDER, dp(2).toFloat(), SELECTED_LAVENDER)
                else rounded(Color.TRANSPARENT, dp(2).toFloat(), Color.TRANSPARENT)
            setOnClickListener { action(this) }
            contentDescription = label
            tooltipText = label
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

    private fun titleIconButton(icon: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            contentDescription = description
            gravity = Gravity.CENTER
            setCompoundDrawablesRelativeWithIntrinsicBounds(fluentIcon(icon), null, null, null)
            compoundDrawableTintList = ColorStateList.valueOf(if (description == "导航") Color.WHITE else TEXT_PRIMARY)
            background = rounded(Color.TRANSPARENT, 0f, Color.TRANSPARENT)
            setOnClickListener { action() }
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
