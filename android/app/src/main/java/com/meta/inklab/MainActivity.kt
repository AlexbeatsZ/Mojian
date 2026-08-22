package com.meta.inklab

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.EditText
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
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class RibbonPage {
    HOME,
    INSERT,
    DRAW,
}

private enum class PendingExport {
    PDF,
    PRIVATE_PACKAGE,
}

private data class InsertTarget(
    val notebookId: String,
    val pageId: String,
    val startX: Float,
    val startY: Float,
)

class MainActivity : Activity() {
    private val activeStrokes = mutableMapOf<Int, InProgressStrokeId>()
    private val viewport = ViewportState()
    private val pressureEraser = PressureEraserController()
    private val shortcutDispatcher = XiaomiStylusShortcutDispatcher()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val toolShelf = ToolShelf()
    private val notebookLibrary = NotebookLibrary.sample()
    private val fullPageToolbarConfig by lazy { FullPageToolbarConfig(loadFullPageToolbarIds()) }
    private val ioExecutor = Executors.newSingleThreadExecutor()
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
    private lateinit var insertContentLayer: InsertContentLayer
    private lateinit var pageContentRepository: PageContentRepository
    private lateinit var assetStore: AssetStore
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
    private var insertSpaceModeEnabled = false
    private var insertSpaceActive = false
    private var insertSpaceStartDocY = 0f
    private var insertSpaceCurrentDocY = 0f
    private var textModeEnabled = false
    private var sectionPaneVisible = true
    private var pagePaneVisible = true
    private var drawingToolbarVisible = true
    private var fullPageMode = false
    private var activeRibbonPage = RibbonPage.DRAW
    private var restoreTextModeAfterTagPlacement = false
    private var imageImportDisplayMode = ImageImportDisplayMode.ASK
    private var pdfPrintoutDisplayMode = ImageImportDisplayMode.ASK
    private var backgroundFormatPreference = CanvasBackgroundFormat.GRID_STANDARD
    private var pendingExport: PendingExport? = null

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
        loadPreferences()
        pageContentRepository = PageContentRepository(filesDir)
        assetStore = AssetStore(this, pageContentRepository)
        currentBrush = brushFor(toolShelf.selected)
        setContentView(buildInterface())
        dryInkView.backgroundFormat = backgroundFormatPreference
        pressureEraser.stationarySlop = dp(2).toFloat()
        syncPressureEraserWithTool()
        bindInkPipeline()
        renderWorkspace()
        renderToolShelf()
        renderToolDetails()
        wetInkView.post { wetInkView.eagerInit() }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uris = selectedUris(data)
        when (requestCode) {
            REQUEST_ATTACHMENT -> uris.firstOrNull()?.let(::importAttachment)
            REQUEST_PDF_PRINTOUT -> uris.firstOrNull()?.let { uri ->
                chooseDisplayFilter(pdfPrintoutDisplayMode, "PDF 打印样式如何显示？") { inverted -> importPdfPrintout(uri, inverted) }
            }
            REQUEST_SPREADSHEET -> uris.firstOrNull()?.let(::importSpreadsheet)
            REQUEST_IMAGES -> if (uris.isNotEmpty()) {
                chooseDisplayFilter(imageImportDisplayMode, "这批图片如何显示？") { inverted -> importImages(uris, inverted) }
            }
            REQUEST_STICKER_IMPORT -> if (uris.isNotEmpty()) importStickerPresets(uris)
            REQUEST_EXPORT -> pendingExport?.let { type -> exportToUri(data.data ?: return, type) }.also { pendingExport = null }
        }
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
                    addView(backstageNavItem("导出", false) { dialog.dismiss(); showExportDialog() }, LinearLayout.LayoutParams(MATCH, dp(42)))
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
                        persistWorkspacePreferences()
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
                        persistWorkspacePreferences()
                    }
                    addView(settingsRow("笔迹预测", "使用预测触点降低快速书写时的视觉延迟。", predictionValue), LinearLayout.LayoutParams(MATCH, dp(72)))

                    val pageModeValue = settingsValueButton(if (workspace.pagePaneMode == PagePaneMode.CURRENT_SECTION) "本层" else "递归")
                    pageModeValue.setOnClickListener {
                        val mode = if (workspace.pagePaneMode == PagePaneMode.CURRENT_SECTION) PagePaneMode.RECURSIVE else PagePaneMode.CURRENT_SECTION
                        notebookLibrary.setPagePaneMode(mode)
                        pageModeValue.text = if (mode == PagePaneMode.CURRENT_SECTION) "本层" else "递归"
                        persistWorkspacePreferences()
                        renderPages()
                    }
                    addView(settingsRow("页面栏显示范围", "本层只显示当前分区页面；递归还显示所有子分区及其页面。", pageModeValue), LinearLayout.LayoutParams(MATCH, dp(72)))

                    val imageFilterValue = settingsValueButton(imageImportDisplayMode.label)
                    imageFilterValue.setOnClickListener {
                        imageImportDisplayMode = nextImportDisplayMode(imageImportDisplayMode)
                        imageFilterValue.text = imageImportDisplayMode.label
                        persistWorkspacePreferences()
                    }
                    addView(
                        settingsRow(
                            "图片导入显示",
                            "只改变画布查看效果；原图与导出始终保持不变。",
                            imageFilterValue,
                        ),
                        LinearLayout.LayoutParams(MATCH, dp(72)),
                    )

                    val pdfFilterValue = settingsValueButton(pdfPrintoutDisplayMode.label)
                    pdfFilterValue.setOnClickListener {
                        pdfPrintoutDisplayMode = nextImportDisplayMode(pdfPrintoutDisplayMode)
                        pdfFilterValue.text = pdfPrintoutDisplayMode.label
                        persistWorkspacePreferences()
                    }
                    addView(
                        settingsRow(
                            "PDF 打印样式显示",
                            "逐页导入时使用查看滤镜；原 PDF 与导出页面不反色。",
                            pdfFilterValue,
                        ),
                        LinearLayout.LayoutParams(MATCH, dp(72)),
                    )

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

    private fun loadPreferences() {
        val prefs = getSharedPreferences(OPTIONS_PREFERENCES, MODE_PRIVATE)
        predictionEnabled = prefs.getBoolean(KEY_PREDICTION_ENABLED, true)
        imageImportDisplayMode =
            runCatching { ImageImportDisplayMode.valueOf(prefs.getString(KEY_IMAGE_IMPORT_DISPLAY_MODE, "") ?: "") }
                .getOrDefault(ImageImportDisplayMode.ASK)
        pdfPrintoutDisplayMode =
            runCatching { ImageImportDisplayMode.valueOf(prefs.getString(KEY_PDF_PRINTOUT_DISPLAY_MODE, "") ?: "") }
                .getOrDefault(ImageImportDisplayMode.ASK)
        sectionPaneVisible = prefs.getBoolean(KEY_SECTION_PANE_VISIBLE, true)
        pagePaneVisible = prefs.getBoolean(KEY_PAGE_PANE_VISIBLE, true)
        pressureEraser.pressureThreshold = prefs.getFloat(KEY_PRESSURE_THRESHOLD, 0.78f)
        pressureEraser.dwellMillis = prefs.getLong(KEY_PRESSURE_DWELL_MILLIS, 140L)

        val modeStr = prefs.getString(KEY_PAGE_PANE_MODE, PagePaneMode.CURRENT_SECTION.name)
        val mode = runCatching { PagePaneMode.valueOf(modeStr ?: "") }.getOrDefault(PagePaneMode.CURRENT_SECTION)
        notebookLibrary.setPagePaneMode(mode)

        val shelfJson = prefs.getString(KEY_TOOL_SHELF, "")
        if (!shelfJson.isNullOrBlank()) {
            ToolShelf.fromJson(shelfJson)?.let { loadedShelf ->
                toolShelf.restoreFrom(loadedShelf)
            }
        }
        val savedPenId = prefs.getString(KEY_LAST_PEN_TOOL_ID, "")
        if (!savedPenId.isNullOrEmpty() && toolShelf.tools.any { it.id == savedPenId }) {
            lastPenToolId = savedPenId
        }

        val activeNotebookId = prefs.getString(KEY_ACTIVE_NOTEBOOK_ID, "")
        if (!activeNotebookId.isNullOrEmpty() && notebookLibrary.notebooks.any { it.id == activeNotebookId }) {
            notebookLibrary.selectNotebook(activeNotebookId)
        }
        val focusedSectionId = prefs.getString(KEY_FOCUSED_SECTION_ID, null)
        if (focusedSectionId != null) {
            if (focusedSectionId.isNotEmpty() && workspace.flattenSections().any { it.section.id == focusedSectionId }) {
                workspace.selectSection(focusedSectionId)
            } else {
                workspace.focusNotebook()
            }
        }
        val selectedPageId = prefs.getString(KEY_SELECTED_PAGE_ID, "")
        if (!selectedPageId.isNullOrEmpty()) {
            for (row in workspace.flattenSections()) {
                if (row.section.pages.any { it.id == selectedPageId }) {
                    workspace.selectPage(row.section.id, selectedPageId)
                    break
                }
            }
        }

        val savedBg = prefs.getString(KEY_BACKGROUND_FORMAT, CanvasBackgroundFormat.GRID_STANDARD.name)
        backgroundFormatPreference = runCatching { CanvasBackgroundFormat.valueOf(savedBg ?: "") }.getOrDefault(CanvasBackgroundFormat.GRID_STANDARD)
    }

    private fun nextImportDisplayMode(mode: ImageImportDisplayMode): ImageImportDisplayMode =
        when (mode) {
            ImageImportDisplayMode.ASK -> ImageImportDisplayMode.INVERT
            ImageImportDisplayMode.INVERT -> ImageImportDisplayMode.ORIGINAL
            ImageImportDisplayMode.ORIGINAL -> ImageImportDisplayMode.ASK
        }

    private fun persistToolShelf() {
        getSharedPreferences(OPTIONS_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(KEY_TOOL_SHELF, toolShelf.toJson())
            .putString(KEY_LAST_PEN_TOOL_ID, lastPenToolId)
            .apply()
    }

    private fun persistWorkspacePreferences() {
        getSharedPreferences(OPTIONS_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREDICTION_ENABLED, predictionEnabled)
            .putString(KEY_IMAGE_IMPORT_DISPLAY_MODE, imageImportDisplayMode.name)
            .putString(KEY_PDF_PRINTOUT_DISPLAY_MODE, pdfPrintoutDisplayMode.name)
            .putBoolean(KEY_SECTION_PANE_VISIBLE, sectionPaneVisible)
            .putBoolean(KEY_PAGE_PANE_VISIBLE, pagePaneVisible)
            .putString(KEY_PAGE_PANE_MODE, workspace.pagePaneMode.name)
            .putFloat(KEY_PRESSURE_THRESHOLD, pressureEraser.pressureThreshold)
            .putLong(KEY_PRESSURE_DWELL_MILLIS, pressureEraser.dwellMillis)
            .putString(KEY_ACTIVE_NOTEBOOK_ID, notebookLibrary.activeNotebookId)
            .putString(KEY_FOCUSED_SECTION_ID, workspace.focusedSectionId.orEmpty())
            .putString(KEY_SELECTED_PAGE_ID, workspace.selectedPageId)
            .putString(KEY_BACKGROUND_FORMAT, dryInkView.backgroundFormat.name)
            .apply()
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
            id in setOf("draw:undo", "draw:cursor", "draw:add", "draw:pan", "draw:insert_space", "draw:rule_lines", "draw:clear") -> true
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
        row.addView(ribbonTab("插入", activeRibbonPage == RibbonPage.INSERT) { selectRibbonPage(RibbonPage.INSERT) })
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
            when (page) {
                RibbonPage.HOME -> buildHomeRibbon()
                RibbonPage.INSERT -> buildInsertRibbon()
                RibbonPage.DRAW -> buildDrawingRibbon()
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        when (page) {
            RibbonPage.HOME -> setTextMode(true, rerender = false)
            RibbonPage.INSERT -> setTextMode(false, rerender = false)
            RibbonPage.DRAW -> {
                renderToolShelf()
                renderToolDetails()
            }
        }
    }

    private fun buildInsertRibbon(): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(TOOLBAR_BACKGROUND)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(2), dp(10), dp(2))
                    addView(insertCommandButton("▦", "表格", ::showTablePicker))
                    addView(verticalDivider())
                    addView(insertCommandButton("⌕", "文件附件") { launchDocumentPicker(REQUEST_ATTACHMENT, "*/*") })
                    addView(insertCommandButton("▤", "文件打印样式") { launchDocumentPicker(REQUEST_PDF_PRINTOUT, "application/pdf") })
                    addView(insertCommandButton("X", "电子表格") { launchSpreadsheetPicker() }, LinearLayout.LayoutParams(dp(92), MATCH))
                    addView(verticalDivider())
                    addView(insertCommandButton("▧", "图片") { launchImagePicker(REQUEST_IMAGES) })
                    addView(insertCommandButton("★", "贴纸") { showStickerPicker() })
                    addView(verticalDivider())
                    addView(insertCommandButton("⌁", "链接") { showNoteLinkPicker() })
                },
                ViewGroup.LayoutParams(WRAP, MATCH),
            )
        }

    private fun insertCommandButton(glyph: String, label: String, action: (View) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(Color.TRANSPARENT, dp(2).toFloat(), Color.TRANSPARENT)
            addView(
                TextView(this@MainActivity).apply {
                    text = glyph
                    gravity = Gravity.CENTER
                    setTextColor(if (glyph == "X") Color.rgb(73, 170, 107) else TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (glyph == "X") 15f else 19f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                },
                LinearLayout.LayoutParams(dp(25), MATCH).apply { marginEnd = dp(5) },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = label
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    maxLines = 1
                },
                LinearLayout.LayoutParams(WRAP, MATCH),
            )
            setOnClickListener { action(this) }
            tooltipText = label
            layoutParams = LinearLayout.LayoutParams(WRAP, MATCH)
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
                    insertContentLayer.invalidate()
                    tagLayer.invalidate()
                    selectionOverlay.invalidate()
                    tagLayer.focusTag(index)
                    true
                }
            }
            show()
        }
    }

    private fun handleInsertSpace(anchor: View) {
        setTextMode(false, rerender = false)
        tagLayer.cancelPlacement()
        singleFingerPanEnabled = false
        transformGestureActive = false
        toolDetailPopup?.dismiss()
        toolDetailPopup = null
        insertSpaceActive = false
        dryInkView.setInsertSpaceHoverY(null)
        dryInkView.setInsertSpaceGuide(null, null)
        insertSpaceModeEnabled = !insertSpaceModeEnabled
        renderToolShelf()
        Toast.makeText(
            this,
            if (insertSpaceModeEnabled) "已开启插入空白空间：笔尖悬浮可预览基准线，按下并向下拖动可推移下方内容" else "已退出插入空白空间",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun handleInsertSpaceGesture(event: MotionEvent): Boolean {
        val fingerIndices = (0 until event.pointerCount).filter { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
        if (fingerIndices.size >= 2) {
            if (insertSpaceActive) {
                insertSpaceActive = false
                dryInkView.setInsertSpaceGuide(null, null)
            }
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val docPoint = viewport.viewToDocument(event.getX(pointerIndex), event.getY(pointerIndex))
                insertSpaceStartDocY = docPoint.y
                insertSpaceCurrentDocY = docPoint.y
                insertSpaceActive = true
                dryInkView.setInsertSpaceHoverY(null)
                dryInkView.setInsertSpaceGuide(insertSpaceStartDocY, insertSpaceCurrentDocY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (insertSpaceActive) {
                    val docPoint = viewport.viewToDocument(event.x, event.y)
                    insertSpaceCurrentDocY = docPoint.y
                    dryInkView.setInsertSpaceGuide(insertSpaceStartDocY, insertSpaceCurrentDocY)
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                if (insertSpaceActive) {
                    val deltaY = insertSpaceCurrentDocY - insertSpaceStartDocY
                    if (kotlin.math.abs(deltaY) >= 2f) {
                        dryInkView.insertVerticalSpace(insertSpaceStartDocY, deltaY)
                        tagLayer.insertVerticalSpace(insertSpaceStartDocY, deltaY)
                        insertContentLayer.insertVerticalSpace(insertSpaceStartDocY, deltaY)
                    }
                    insertSpaceActive = false
                    dryInkView.setInsertSpaceGuide(null, null)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (insertSpaceActive) {
                    insertSpaceActive = false
                    dryInkView.setInsertSpaceGuide(null, null)
                    return true
                }
            }
        }
        return insertSpaceActive
    }

    private fun showBackgroundFormatMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            CanvasBackgroundFormat.entries.forEach { format ->
                val prefix = if (dryInkView.backgroundFormat == format) "✓ " else "   "
                menu.add("$prefix${format.label}").setOnMenuItemClickListener {
                    dryInkView.backgroundFormat = format
                    persistWorkspacePreferences()
                    Toast.makeText(this@MainActivity, "页面背景：${format.label}", Toast.LENGTH_SHORT).show()
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
        insertContentLayer = InsertContentLayer(this, viewport, pageContentRepository)
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
        insertContentLayer.onDocumentChanged = pageContentRepository::save
        insertContentLayer.onObjectActivated = ::activateInsertObject
        dryInkView.setViewport(viewport.documentToViewMatrix())
        paper.addView(dryInkView, FrameLayout.LayoutParams(MATCH, MATCH))
        paper.addView(wetInkView, FrameLayout.LayoutParams(MATCH, MATCH))
        paper.addView(insertContentLayer, FrameLayout.LayoutParams(MATCH, MATCH))
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
                        persistWorkspacePreferences()
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
                            persistWorkspacePreferences()
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
        bindSelectedPageContent()
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
                    persistWorkspacePreferences()
                    renderPages()
                }
            }
        pageList.addView(item, LinearLayout.LayoutParams(MATCH, dp(58)).apply { setMargins(dp(6), dp(1), dp(6), dp(1)) })
    }

    private fun bindSelectedPageContent() {
        val pageId = workspace.selectedPageId.takeIf(String::isNotEmpty)
        val document = pageId?.let { pageContentRepository.load(workspace.id, it) }
        insertContentLayer.bind(document)
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
            add(FullPageToolDescriptor("draw:insert_space", "插入空白空间", "insert_space"))
            add(FullPageToolDescriptor("draw:rule_lines", "设置背景格式", "rule_lines"))
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
                    insertSpaceModeEnabled = false
                    insertSpaceActive = false
                    dryInkView.setInsertSpaceHoverY(null)
                    dryInkView.setInsertSpaceGuide(null, null)
                    transformGestureActive = false
                    renderToolShelf()
                }
            "draw:insert_space" ->
                commandButton("insert_space", "插入空白空间", insertSpaceModeEnabled, preserveColors = true) { anchor ->
                    handleInsertSpace(anchor)
                }
            "draw:rule_lines" ->
                commandButton("rule_lines", "设置背景格式", false, preserveColors = true) { anchor ->
                    showBackgroundFormatMenu(anchor)
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
                insertSpaceModeEnabled = false
                insertSpaceActive = false
                dryInkView.setInsertSpaceHoverY(null)
                dryInkView.setInsertSpaceGuide(null, null)
                transformGestureActive = false
                toolDetailPopup?.dismiss()
                toolDetailPopup = null
                renderToolShelf()
            },
            LinearLayout.LayoutParams(dp(40), dp(42)),
        )
        row.addView(verticalDivider())
        row.addView(
            commandButton("insert_space", "插入空白空间", selected = insertSpaceModeEnabled, preserveColors = true) { anchor ->
                handleInsertSpace(anchor)
            },
            LinearLayout.LayoutParams(dp(40), dp(42)),
        )
        row.addView(
            commandButton("rule_lines", "设置背景格式", selected = false, preserveColors = true) { anchor ->
                showBackgroundFormatMenu(anchor)
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
                        persistToolShelf()
                        rebuildBrush()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                toolDetailRow.addView(
                    parameterControl("压感", tool.pressurePercent, 400, { "$it%" }) {
                        tool.pressurePercent = it
                        persistToolShelf()
                        rebuildBrush()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                toolDetailRow.addView(
                    parameterControl("出墨", tool.inkFlowPercent, 200, { "$it%" }) {
                        tool.inkFlowPercent = it
                        persistToolShelf()
                        rebuildBrush()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                addToolDetailLine(
                    detailChip(if (tool.pressureEraseEnabled) "压感橡皮 开" else "压感橡皮 关", tool.pressureEraseEnabled) {
                        tool.pressureEraseEnabled = !tool.pressureEraseEnabled
                        persistToolShelf()
                        syncPressureEraserWithTool()
                        renderToolDetails()
                    },
                )
                toolDetailRow.addView(
                    parameterControl("按压力度", ((pressureEraser.pressureThreshold * 100).roundToInt() - 40).coerceIn(0, 60), 60, { "${it + 40}%" }) {
                        pressureEraser.pressureThreshold = (it + 40) / 100f
                        persistWorkspacePreferences()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                toolDetailRow.addView(
                    parameterControl("按压时间", ((pressureEraser.dwellMillis - 50) / 10).toInt().coerceIn(0, 45), 45, { "${50 + it * 10} ms" }) {
                        pressureEraser.dwellMillis = (50 + it * 10).toLong()
                        persistWorkspacePreferences()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
            }

            DrawingToolKind.HIGHLIGHTER -> {
                addPaletteControls(tool)
                toolDetailRow.addView(
                    parameterControl("宽度", ((tool.widthMm - 2f) * 2f).roundToInt().coerceIn(0, 20), 20, { "${2f + it / 2f} mm" }) {
                        tool.widthMm = 2f + it / 2f
                        persistToolShelf()
                        rebuildBrush()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                toolDetailRow.addView(
                    parameterControl("透明度", (tool.opacityPercent - 10).coerceIn(0, 60), 60, { "${it + 10}%" }) {
                        tool.opacityPercent = it + 10
                        persistToolShelf()
                        rebuildBrush()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
            }

            DrawingToolKind.ERASER -> {
                toolDetailRow.addView(
                    parameterControl("大小", ((tool.eraserRadiusMm - 2f) * 2f).roundToInt().coerceIn(0, 28), 28, { "${2f + it / 2f} mm" }) {
                        tool.eraserRadiusMm = 2f + it / 2f
                        persistToolShelf()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                toolDetailRow.addView(
                    parameterControl("按压力度", ((pressureEraser.pressureThreshold * 100).roundToInt() - 40).coerceIn(0, 60), 60, { "${it + 40}%" }) {
                        pressureEraser.pressureThreshold = (it + 40) / 100f
                        persistWorkspacePreferences()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                toolDetailRow.addView(
                    parameterControl("按压时间", ((pressureEraser.dwellMillis - 50) / 10).toInt().coerceIn(0, 45), 45, { "${50 + it * 10} ms" }) {
                        pressureEraser.dwellMillis = (50 + it * 10).toLong()
                        persistWorkspacePreferences()
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
                persistToolShelf()
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
                        persistToolShelf()
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
        wetInkView.setOnHoverListener { _, event ->
            if (insertSpaceModeEnabled) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER,
                    MotionEvent.ACTION_HOVER_MOVE -> {
                        val docPoint = viewport.viewToDocument(event.x, event.y)
                        dryInkView.setInsertSpaceHoverY(docPoint.y)
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        dryInkView.setInsertSpaceHoverY(null)
                    }
                }
                true
            } else {
                false
            }
        }
    }

    private fun handleMotionEvent(event: MotionEvent) {
        if (insertSpaceModeEnabled && handleInsertSpaceGesture(event)) return
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
        persistToolShelf()
        tagLayer.cancelPlacement()
        restoreTextModeAfterTagPlacement = false
        setTextMode(false, rerender = false)
        singleFingerPanEnabled = false
        insertSpaceModeEnabled = false
        insertSpaceActive = false
        dryInkView.setInsertSpaceHoverY(null)
        dryInkView.setInsertSpaceGuide(null, null)
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
            insertSpaceModeEnabled = false
            insertSpaceActive = false
            dryInkView.setInsertSpaceHoverY(null)
            dryInkView.setInsertSpaceGuide(null, null)
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
        insertContentLayer.invalidate()
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
        persistWorkspacePreferences()
        applyChromeVisibility()
    }

    private fun togglePagePane() {
        pagePaneVisible = !pagePaneVisible
        persistWorkspacePreferences()
        applyChromeVisibility()
    }

    private fun applyChromeVisibility() {
        if (!::ribbonTabs.isInitialized) return
        if (!fullPageMode) drawingToolbarVisible = true
        ribbonTabs.visibility = if (fullPageMode) View.GONE else View.VISIBLE
        drawingRibbon.visibility = if (fullPageMode) View.GONE else View.VISIBLE
        floatingToolbox.visibility = if (fullPageMode && drawingToolbarVisible) View.VISIBLE else View.GONE
        toolbarToggle.visibility = if (fullPageMode) View.GONE else View.VISIBLE
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
                persistWorkspacePreferences()
                renderWorkspace()
                true
            }
            menu.add("新建分区").setOnMenuItemClickListener {
                workspace.addSectionAtFocus()
                persistWorkspacePreferences()
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
            .setMessage("将清空当前页面的笔迹、文本、标记和所有插入内容；嵌入文件也会从本页资产中删除，无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                dryInkView.clear()
                textInputLayer.clearAll()
                tagLayer.clear()
                insertContentLayer.currentDocument()?.let { assetStore.clearPageAssets(it.notebookId, it.pageId) }
                insertContentLayer.clearObjects()
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
        val selected = !singleFingerPanEnabled && !insertSpaceModeEnabled && !textModeEnabled && tool.id == toolShelf.selectedId
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

    private fun commandButton(
        icon: String,
        label: String,
        selected: Boolean,
        preserveColors: Boolean = icon in setOf("insert_space", "rule_lines"),
        action: (View) -> Unit,
    ): View =
        FrameLayout(this).apply {
            background = toggleBackground(selected)
            val iconView =
                ImageView(this@MainActivity).apply {
                    setImageDrawable(fluentIcon(icon))
                    if (!preserveColors) {
                        imageTintList = ColorStateList.valueOf(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
                    }
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

    private fun showTablePicker(anchor: View) {
        if (insertTarget() == null) {
            Toast.makeText(this, "请先选择页面", Toast.LENGTH_SHORT).show()
            return
        }
        val popupContent =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(8))
                setBackgroundColor(TOOLBAR_BACKGROUND)
                addView(
                    TextView(this@MainActivity).apply {
                        text = "插入表格"
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(MATCH, dp(26)),
                )
            }
        val popup = PopupWindow(popupContent, WRAP, WRAP, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(rounded(TOOLBAR_BACKGROUND, dp(3).toFloat(), BORDER_STRONG))
            elevation = dp(10).toFloat()
        }
        val grid = GridLayout(this).apply { columnCount = 8; rowCount = 6 }
        for (row in 1..6) {
            for (column in 1..8) {
                grid.addView(
                    TextView(this).apply {
                        text = ""
                        contentDescription = "$row 行 $column 列"
                        background = rounded(Color.rgb(248, 248, 248), 0f, Color.rgb(69, 69, 69))
                        setOnClickListener {
                            insertTable(row, column)
                            popup.dismiss()
                        }
                    },
                    GridLayout.LayoutParams().apply {
                        width = dp(28)
                        height = dp(28)
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    },
                )
            }
        }
        popupContent.addView(grid)
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, location[0], location[1] + anchor.height + dp(2))
    }

    private fun insertTable(rows: Int, columns: Int) {
        val target = insertTarget() ?: return
        val cellWidth = dp(82).toFloat()
        val width = (columns * cellWidth).coerceAtMost(dp(650).toFloat())
        val table =
            InsertObject.Table(
                id = "table-${UUID.randomUUID()}",
                documentX = target.startX,
                documentY = target.startY,
                width = width,
                height = rows * dp(40).toFloat(),
                rows = rows,
                columns = columns,
            )
        insertContentLayer.addObject(table)
        showTableEditor(table)
    }

    private fun showTableEditor(table: InsertObject.Table) {
        val editors = mutableListOf<EditText>()
        val rows =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                for (rowIndex in 0 until table.rows) {
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            for (columnIndex in 0 until table.columns) {
                                val cellIndex = rowIndex * table.columns + columnIndex
                                val editor =
                                    EditText(this@MainActivity).apply {
                                        setText(table.cells.getOrElse(cellIndex) { "" })
                                        setTextColor(TEXT_PRIMARY)
                                        setHintTextColor(TEXT_SECONDARY)
                                        hint = "${rowIndex + 1},${columnIndex + 1}"
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                        setSingleLine(true)
                                        background = rounded(Color.rgb(31, 31, 31), 0f, BORDER_STRONG)
                                        setPadding(dp(7), 0, dp(7), 0)
                                    }
                                editors += editor
                                addView(editor, LinearLayout.LayoutParams(dp(112), dp(42)).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
                            }
                        },
                        LinearLayout.LayoutParams(WRAP, dp(44)),
                    )
                }
            }
        AlertDialog.Builder(this)
            .setTitle("编辑 ${table.rows} × ${table.columns} 表格")
            .setView(HorizontalScrollView(this).apply { addView(ScrollView(this@MainActivity).apply { addView(rows) }) })
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                editors.forEachIndexed { index, editor -> table.cells[index] = editor.text.toString() }
                insertContentLayer.notifyObjectChanged()
            }
            .show()
    }

    private fun launchDocumentPicker(requestCode: Int, mimeType: String, allowMultiple: Boolean = false, extraMimeTypes: Array<String>? = null) {
        if (insertTarget() == null) {
            Toast.makeText(this, "请先选择页面", Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                extraMimeTypes?.let { putExtra(Intent.EXTRA_MIME_TYPES, it) }
            },
            requestCode,
        )
    }

    private fun launchSpreadsheetPicker() {
        launchDocumentPicker(
            REQUEST_SPREADSHEET,
            "*/*",
            extraMimeTypes =
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "text/csv",
                    "text/tab-separated-values",
                ),
        )
    }

    private fun launchImagePicker(requestCode: Int) {
        launchDocumentPicker(requestCode, "image/*", allowMultiple = true)
    }

    private fun selectedUris(data: Intent): List<Uri> =
        buildList {
            data.clipData?.let { clip -> for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri) }
            data.data?.let { if (it !in this) add(it) }
        }

    private fun chooseDisplayFilter(mode: ImageImportDisplayMode, title: String, action: (Boolean) -> Unit) {
        when (mode) {
            ImageImportDisplayMode.INVERT -> action(true)
            ImageImportDisplayMode.ORIGINAL -> action(false)
            ImageImportDisplayMode.ASK ->
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setItems(arrayOf("反色保色显示", "保持原图显示")) { _, which -> action(which == 0) }
                    .setNegativeButton("取消", null)
                    .show()
        }
    }

    private fun importAttachment(uri: Uri) {
        val target = insertTarget() ?: return
        Toast.makeText(this, "正在嵌入附件…", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            runCatching {
                val asset = assetStore.importUri(target.notebookId, target.pageId, uri)
                InsertObject.Attachment(
                    id = "attachment-${UUID.randomUUID()}",
                    documentX = target.startX,
                    documentY = target.startY,
                    width = dp(430).toFloat(),
                    height = dp(68).toFloat(),
                    assetName = asset.storedName,
                    displayName = asset.displayName,
                    mimeType = asset.mimeType,
                    sizeBytes = asset.sizeBytes,
                )
            }.onSuccess { appendImportedObjects(target, listOf(it), "附件已嵌入") }
                .onFailure { reportBackgroundFailure("附件导入失败", it) }
        }
    }

    private fun importImages(uris: List<Uri>, inverted: Boolean) {
        val target = insertTarget() ?: return
        val maxWidth = dp(620).toFloat()
        Toast.makeText(this, "正在导入 ${uris.size} 张图片…", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            runCatching {
                var y = target.startY
                uris.map { uri ->
                    val asset = assetStore.importUri(target.notebookId, target.pageId, uri)
                    val (width, height) = fittedImageSize(asset.file, maxWidth)
                    InsertObject.Image(
                        id = "image-${UUID.randomUUID()}",
                        documentX = target.startX,
                        documentY = y,
                        width = width,
                        height = height,
                        assetName = asset.storedName,
                        displayName = asset.displayName,
                        mimeType = asset.mimeType,
                        sourceKind = ImageSourceKind.IMAGE,
                        viewInverted = inverted,
                    ).also { y += height + dp(18) }
                }
            }.onSuccess { appendImportedObjects(target, it, "已导入 ${it.size} 张图片") }
                .onFailure { reportBackgroundFailure("图片导入失败", it) }
        }
    }

    private fun importPdfPrintout(uri: Uri, inverted: Boolean) {
        val target = insertTarget() ?: return
        val displayWidth = dp(640).toFloat()
        Toast.makeText(this, "正在逐页导入 PDF…", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            runCatching {
                val source = assetStore.importUri(target.notebookId, target.pageId, uri)
                val outputDirectory = pageContentRepository.assetDirectory(target.notebookId, target.pageId)
                val objects = mutableListOf<InsertObject>()
                var y = target.startY
                ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        for (index in 0 until renderer.pageCount) {
                            renderer.openPage(index).use { page ->
                                val renderWidth = (page.width * 2).coerceAtMost(1800).coerceAtLeast(1)
                                val renderHeight = (page.height * renderWidth.toFloat() / page.width).roundToInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                                Canvas(bitmap).drawColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val storedName = "${UUID.randomUUID()}-pdf-page-${index + 1}.png"
                                val file = File(outputDirectory, storedName)
                                file.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                bitmap.recycle()
                                val height = displayWidth * renderHeight / renderWidth
                                objects +=
                                    InsertObject.Image(
                                        id = "printout-${UUID.randomUUID()}",
                                        documentX = target.startX,
                                        documentY = y,
                                        width = displayWidth,
                                        height = height,
                                        assetName = storedName,
                                        displayName = "${source.displayName} · ${index + 1}",
                                        mimeType = "image/png",
                                        sourceKind = ImageSourceKind.PDF_PAGE,
                                        viewInverted = inverted,
                                        sourceAssetName = source.storedName,
                                        sourcePageNumber = index + 1,
                                    )
                                y += height + dp(18)
                            }
                        }
                    }
                }
                objects
            }.onSuccess { appendImportedObjects(target, it, "已导入 ${it.size} 页 PDF") }
                .onFailure { reportBackgroundFailure("PDF 导入失败", it) }
        }
    }

    private fun importSpreadsheet(uri: Uri) {
        val target = insertTarget() ?: return
        Toast.makeText(this, "正在生成电子表格预览…", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            runCatching {
                val asset = assetStore.importUri(target.notebookId, target.pageId, uri)
                val preview = XlsxPreviewParser.preview(asset.file)
                val columns = preview.maxOfOrNull(List<String>::size)?.coerceAtLeast(1) ?: 1
                InsertObject.Spreadsheet(
                    id = "spreadsheet-${UUID.randomUUID()}",
                    documentX = target.startX,
                    documentY = target.startY,
                    width = (columns * dp(105)).coerceAtMost(dp(720)).toFloat(),
                    height = (dp(48) + preview.size.coerceAtLeast(1) * dp(34)).toFloat(),
                    assetName = asset.storedName,
                    displayName = asset.displayName,
                    mimeType = asset.mimeType,
                    previewRows = preview,
                )
            }.onSuccess { appendImportedObjects(target, listOf(it), "电子表格已嵌入，可点击打开编辑") }
                .onFailure { reportBackgroundFailure("电子表格导入失败", it) }
        }
    }

    private fun fittedImageSize(file: File, maximumWidth: Float): Pair<Float, Float> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val rawWidth = options.outWidth.coerceAtLeast(1).toFloat()
        val rawHeight = options.outHeight.coerceAtLeast(1).toFloat()
        val scale = minOf(1f, maximumWidth / rawWidth)
        return rawWidth * scale to rawHeight * scale
    }

    private fun appendImportedObjects(target: InsertTarget, objects: List<InsertObject>, message: String) {
        runOnUiThread {
            val current = insertContentLayer.currentDocument()
            if (current?.notebookId == target.notebookId && current.pageId == target.pageId) {
                insertContentLayer.addObjects(objects)
            } else {
                pageContentRepository.load(target.notebookId, target.pageId).also { document ->
                    document.objects += objects
                    pageContentRepository.save(document)
                }
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reportBackgroundFailure(prefix: String, error: Throwable) {
        runOnUiThread { Toast.makeText(this, "$prefix：${error.message ?: "未知错误"}", Toast.LENGTH_LONG).show() }
    }

    private fun showStickerPicker() {
        if (insertTarget() == null) {
            Toast.makeText(this, "请先选择页面", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(18))
                setBackgroundColor(TOOLBAR_BACKGROUND)
                addView(
                    TextView(this@MainActivity).apply {
                        text = "贴纸"
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    },
                    LinearLayout.LayoutParams(MATCH, dp(38)),
                )
            }
        val grid = GridLayout(this).apply { columnCount = 4 }
        BuiltInSticker.entries.forEach { kind ->
            grid.addView(stickerTile(kind.label, StickerFactory.render(kind)) { dialog.dismiss(); insertBuiltInSticker(kind) })
        }
        assetStore.customStickerFiles().forEach { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) grid.addView(stickerTile(file.nameWithoutExtension.take(10), bitmap) { dialog.dismiss(); insertStickerFile(file, file.name) })
        }
        grid.addView(
            TextView(this).apply {
                text = "+\n导入预设"
                gravity = Gravity.CENTER
                setTextColor(ACCENT_PURPLE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = rounded(Color.rgb(31, 31, 31), dp(3).toFloat(), BORDER_STRONG)
                setOnClickListener { dialog.dismiss(); launchImagePicker(REQUEST_STICKER_IMPORT) }
            },
            GridLayout.LayoutParams().apply { width = dp(92); height = dp(104); setMargins(dp(5), dp(5), dp(5), dp(5)) },
        )
        body.addView(grid)
        dialog.setContentView(body)
        dialog.show()
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(WRAP, WRAP) }
    }

    private fun stickerTile(label: String, bitmap: Bitmap, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(31, 31, 31), dp(3).toFloat(), BORDER_STRONG)
            addView(ImageView(this@MainActivity).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(66), dp(70)))
            addView(TextView(this@MainActivity).apply { text = label; gravity = Gravity.CENTER; setTextColor(TEXT_PRIMARY); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f) }, LinearLayout.LayoutParams(MATCH, dp(26)))
            setOnClickListener { action() }
            layoutParams = GridLayout.LayoutParams().apply { width = dp(92); height = dp(104); setMargins(dp(5), dp(5), dp(5), dp(5)) }
        }

    private fun insertBuiltInSticker(kind: BuiltInSticker) {
        val temporary = File.createTempFile("sticker-", ".png", cacheDir)
        try {
            StickerFactory.write(kind, temporary)
            insertStickerFile(temporary, "${kind.label}.png")
        } finally {
            temporary.delete()
        }
    }

    private fun insertStickerFile(file: File, displayName: String) {
        val target = insertTarget() ?: return
        runCatching {
            val asset = assetStore.copyFile(target.notebookId, target.pageId, file, displayName, "image/png")
            val (width, height) = fittedImageSize(asset.file, dp(180).toFloat())
            InsertObject.Image(
                id = "sticker-${UUID.randomUUID()}",
                documentX = target.startX,
                documentY = target.startY,
                width = width,
                height = height,
                assetName = asset.storedName,
                displayName = displayName,
                mimeType = "image/png",
                sourceKind = ImageSourceKind.STICKER,
            )
        }.onSuccess { insertContentLayer.addObject(it) }
            .onFailure { Toast.makeText(this, "贴纸插入失败：${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun importStickerPresets(uris: List<Uri>) {
        val target = insertTarget() ?: return
        Toast.makeText(this, "正在导入贴纸预设…", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            runCatching { uris.map(assetStore::importStickerPreset) }
                .onSuccess { presets ->
                    var y = target.startY
                    val objects = presets.map { preset ->
                        val asset = assetStore.copyFile(target.notebookId, target.pageId, preset.file, preset.displayName, preset.mimeType)
                        val (width, height) = fittedImageSize(asset.file, dp(180).toFloat())
                        InsertObject.Image(
                            id = "sticker-${UUID.randomUUID()}", target.startX, y, width, height,
                            asset.storedName, preset.displayName, preset.mimeType, ImageSourceKind.STICKER,
                        ).also { y += height + dp(14) }
                    }
                    appendImportedObjects(target, objects, "已加入 ${objects.size} 个贴纸预设并插入页面")
                }
                .onFailure { reportBackgroundFailure("贴纸预设导入失败", it) }
        }
    }

    private fun showNoteLinkPicker() {
        val target = insertTarget() ?: run { Toast.makeText(this, "请先选择页面", Toast.LENGTH_SHORT).show(); return }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(16)) }
        rows.addView(TextView(this).apply { text = "链接到笔记"; setTextColor(TEXT_PRIMARY); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(MATCH, dp(42)))
        notebookLibrary.notebooks.forEach { notebook ->
            fun visit(sections: List<SectionNode>, path: List<String>) {
                sections.forEach { section ->
                    val nextPath = path + section.title
                    section.pages.forEach { page ->
                        rows.addView(
                            TextView(this).apply {
                                text = "${notebook.title}  ›  ${nextPath.joinToString("  ›  ")}  ›  ${page.title}"
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(10), 0, dp(10), 0)
                                setTextColor(Color.rgb(70, 153, 255))
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                background = rounded(Color.TRANSPARENT, dp(2).toFloat(), BORDER_COLOR)
                                setOnClickListener {
                                    insertContentLayer.addObject(
                                        InsertObject.NoteLink(
                                            id = "link-${UUID.randomUUID()}",
                                            documentX = target.startX,
                                            documentY = target.startY,
                                            width = dp(480).toFloat(),
                                            height = dp(38).toFloat(),
                                            targetNotebookId = notebook.id,
                                            targetSectionId = section.id,
                                            targetPageId = page.id,
                                            targetTitle = page.title,
                                        ),
                                    )
                                    dialog.dismiss()
                                }
                            },
                            LinearLayout.LayoutParams(MATCH, dp(42)).apply { bottomMargin = dp(3) },
                        )
                    }
                    visit(section.children, nextPath)
                }
            }
            visit(notebook.sections, emptyList())
        }
        dialog.setContentView(ScrollView(this).apply { setBackgroundColor(TOOLBAR_BACKGROUND); addView(rows) })
        dialog.show()
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(TOOLBAR_BACKGROUND)); setLayout(dp(620), dp(520)) }
    }

    private fun activateInsertObject(item: InsertObject) {
        when (item) {
            is InsertObject.Table -> showTableEditor(item)
            is InsertObject.Attachment -> openEmbeddedAsset(item.assetName, item.mimeType)
            is InsertObject.Spreadsheet -> openEmbeddedAsset(item.assetName, item.mimeType)
            is InsertObject.NoteLink -> openNoteLink(item)
            is InsertObject.Image -> Unit
        }
    }

    private fun openEmbeddedAsset(assetName: String, mimeType: String) {
        val document = insertContentLayer.currentDocument() ?: return
        val file = assetStore.assetFile(document.notebookId, document.pageId, assetName)
        if (!file.isFile) {
            Toast.makeText(this, "嵌入文件已丢失", Toast.LENGTH_SHORT).show()
            return
        }
        val contentUri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show() }
    }

    private fun openNoteLink(link: InsertObject.NoteLink) {
        val notebook = notebookLibrary.notebooks.firstOrNull { it.id == link.targetNotebookId }
        if (notebook == null || !notebookLibrary.selectNotebook(notebook.id) || !notebook.selectSection(link.targetSectionId) || !notebook.selectPage(link.targetSectionId, link.targetPageId)) {
            Toast.makeText(this, "链接的笔记已不存在", Toast.LENGTH_SHORT).show()
            return
        }
        persistWorkspacePreferences()
        renderWorkspace()
    }

    private fun insertTarget(): InsertTarget? {
        if (!::insertContentLayer.isInitialized) return null
        val pageId = workspace.selectedPageId.takeIf(String::isNotEmpty) ?: return null
        val point = insertContentLayer.nextInsertionPoint()
        return InsertTarget(workspace.id, pageId, point.x, point.y)
    }

    private fun showExportDialog() {
        if (workspace.selectedPageId.isEmpty()) {
            Toast.makeText(this, "请先选择要导出的页面", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("导出")
            .setItems(arrayOf("分层 PDF（当前页面）", "墨笺私有格式 .mjn（当前笔记本）")) { _, which ->
                startExport(if (which == 0) PendingExport.PDF else PendingExport.PRIVATE_PACKAGE)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startExport(type: PendingExport) {
        pendingExport = type
        val pageTitle = workspace.selectedPage()?.title ?: "页面"
        val fileName =
            if (type == PendingExport.PDF) "${safeFileName(pageTitle)}.pdf"
            else "${safeFileName(workspace.title)}.mjn"
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                this.type = if (type == PendingExport.PDF) "application/pdf" else "application/zip"
                putExtra(Intent.EXTRA_TITLE, fileName)
            },
            REQUEST_EXPORT,
        )
    }

    private fun exportToUri(uri: Uri, type: PendingExport) {
        val layers = captureExportLayers() ?: return
        val pageId = workspace.selectedPageId
        val notebook = workspace
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            runCatching {
                contentResolver.openOutputStream(uri, "w").use { output ->
                    requireNotNull(output) { "无法写入目标文件" }
                    if (type == PendingExport.PDF) {
                        val rasterLayers = layers.map { (name, bitmap) -> bitmapToPdfLayer(name, bitmap) }
                        LayeredPdfWriter.write(output, rasterLayers)
                    } else {
                        val pngLayers = layers.entries.associate { (name, bitmap) -> name to bitmapToPng(bitmap) }
                        NotebookPackageExporter(pageContentRepository).write(output, notebook, pageId, pngLayers)
                    }
                }
            }.onSuccess { runOnUiThread { Toast.makeText(this, "导出完成", Toast.LENGTH_LONG).show() } }
                .onFailure { reportBackgroundFailure("导出失败", it) }
            layers.values.forEach(Bitmap::recycle)
        }
    }

    private fun captureExportLayers(): LinkedHashMap<String, Bitmap>? {
        val width = dryInkView.width
        val height = dryInkView.height
        if (width <= 0 || height <= 0) {
            Toast.makeText(this, "页面尚未完成布局", Toast.LENGTH_SHORT).show()
            return null
        }
        val background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dryInkView.renderBackgroundForExport(Canvas(background))
        val inserted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        insertContentLayer.renderForExport(Canvas(inserted))
        val inkAndText = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(inkAndText).also { canvas ->
            dryInkView.renderInkForExport(canvas)
            tagLayer.draw(canvas)
            textInputLayer.draw(canvas)
        }
        ExportColorTransform.applyTo(inkAndText)
        return linkedMapOf("页面背景" to background, "插入内容" to inserted, "墨迹与文字" to inkAndText)
    }

    private fun bitmapToPdfLayer(name: String, bitmap: Bitmap): PdfRasterLayer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rgb = ByteArray(pixels.size * 3)
        val alpha = ByteArray(pixels.size)
        var hasTransparency = false
        pixels.forEachIndexed { index, color ->
            rgb[index * 3] = Color.red(color).toByte()
            rgb[index * 3 + 1] = Color.green(color).toByte()
            rgb[index * 3 + 2] = Color.blue(color).toByte()
            alpha[index] = Color.alpha(color).toByte()
            if (Color.alpha(color) != 255) hasTransparency = true
        }
        return PdfRasterLayer(name, bitmap.width, bitmap.height, rgb, alpha.takeIf { hasTransparency })
    }

    private fun bitmapToPng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun safeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "导出" }

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
        private const val KEY_TOOL_SHELF = "tool-shelf"
        private const val KEY_LAST_PEN_TOOL_ID = "last-pen-tool-id"
        private const val KEY_PREDICTION_ENABLED = "prediction-enabled"
        private const val KEY_SECTION_PANE_VISIBLE = "section-pane-visible"
        private const val KEY_PAGE_PANE_VISIBLE = "page-pane-visible"
        private const val KEY_PAGE_PANE_MODE = "page-pane-mode"
        private const val KEY_PRESSURE_THRESHOLD = "pressure-threshold"
        private const val KEY_PRESSURE_DWELL_MILLIS = "pressure-dwell-millis"
        private const val KEY_ACTIVE_NOTEBOOK_ID = "active-notebook-id"
        private const val KEY_FOCUSED_SECTION_ID = "focused-section-id"
        private const val KEY_SELECTED_PAGE_ID = "selected-page-id"
        private const val KEY_BACKGROUND_FORMAT = "background-format"
        private const val KEY_IMAGE_IMPORT_DISPLAY_MODE = "image-import-display-mode"
        private const val KEY_PDF_PRINTOUT_DISPLAY_MODE = "pdf-printout-display-mode"
        private const val REQUEST_ATTACHMENT = 4101
        private const val REQUEST_PDF_PRINTOUT = 4102
        private const val REQUEST_SPREADSHEET = 4103
        private const val REQUEST_IMAGES = 4104
        private const val REQUEST_STICKER_IMPORT = 4105
        private const val REQUEST_EXPORT = 4106
        private const val TOOLBAR_ID_SEPARATOR = "\u001F"
        private val TITLE_PURPLE = Color.rgb(20, 20, 20)
        private val RIBBON_BACKGROUND = Color.rgb(17, 17, 17)
        private val ACCENT_PURPLE = Color.rgb(218, 178, 233)
        private val SELECTED_LAVENDER = Color.rgb(55, 64, 72)
        private val TOOLBAR_BACKGROUND = Color.rgb(35, 35, 35)
        private val NAV_BACKGROUND = Color.rgb(15, 15, 15)
        private val PAGE_LIST_BACKGROUND = Color.rgb(42, 42, 42)
        private val PAPER_COLOR = Color.rgb(ImageViewFilter.TARGET_BACKGROUND_GRAY, ImageViewFilter.TARGET_BACKGROUND_GRAY, ImageViewFilter.TARGET_BACKGROUND_GRAY)
        private val BORDER_COLOR = Color.rgb(61, 61, 61)
        private val BORDER_STRONG = Color.rgb(87, 87, 87)
        private val TEXT_PRIMARY = Color.rgb(244, 244, 244)
        private val TEXT_SECONDARY = Color.rgb(185, 185, 185)
    }
}
