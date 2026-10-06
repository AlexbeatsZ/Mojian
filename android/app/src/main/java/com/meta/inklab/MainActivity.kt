package com.meta.inklab

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.EditText
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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.sqrt
import org.json.JSONObject

private enum class RibbonPage {
    HOME,
    INSERT,
    DRAW,
    VIEW,
}

private enum class PendingExport {
    PDF,
    PRIVATE_PACKAGE,
    SECTION_PACKAGE,
}

private enum class MouseSecondaryGesture {
    NONE,
    PENDING,
    LASSO,
}

private enum class PageCreationTab {
    PAGE,
    PDF,
}

private data class InsertTarget(
    val notebookId: String,
    val pageId: String,
    val startX: Float,
    val startY: Float,
)

private data class PdfPageTarget(
    val notebookId: String,
    val sectionId: String,
    val splitSide: SplitPaneSide?,
)

private data class PageSessionKey(
    val notebookId: String,
    val pageId: String,
)

private data class PageSessionState(
    val strokes: List<Stroke>,
    val tags: List<CanvasTagSnapshot>,
    val textBoxes: List<CanvasTextSnapshot>,
    val viewport: ViewportSnapshot,
)

private data class ExportSnapshot(
    val layers: LinkedHashMap<String, Bitmap>,
    val pageSize: PdfOutputPageSize?,
)

private sealed interface NavigationDragPayload {
    data class Section(
        val notebookId: String,
        val sectionId: String,
    ) : NavigationDragPayload

    data class Page(
        val notebookId: String,
        val sectionId: String,
        val pageId: String,
    ) : NavigationDragPayload
}

private data class SectionRowTag(
    val notebookId: String,
    val sectionId: String,
)

private data class PageRowTag(
    val notebookId: String,
    val sectionId: String,
    val pageId: String,
)

private data class NavigationDropTarget(
    val tag: Any,
    val after: Boolean,
)

private sealed interface PageNavigationClipboard {
    val notebookId: String
    val sectionId: String
    val pageId: String

    data class Copy(
        override val notebookId: String,
        override val sectionId: String,
        override val pageId: String,
    ) : PageNavigationClipboard

    data class Cut(
        override val notebookId: String,
        override val sectionId: String,
        override val pageId: String,
    ) : PageNavigationClipboard
}

private data class SectionExportTarget(
    val notebookId: String,
    val sectionId: String,
)

private data class InsertObjectClipboard(
    val source: PageSessionKey,
    val json: JSONObject,
)

class MainActivity : Activity() {
    private val activeStrokes = mutableMapOf<Int, InProgressStrokeId>()
    private val straighten = HoldToStraighten()
    private var shapePointerId: Int? = null
    private var shapeStart = ShapePoint(0f, 0f)
    private var shapeEnd = shapeStart
    private var shapePreset: ToolPreset? = null
    private var shapeBrush: Brush? = null
    private var restoringPageInk = false
    private val shapeSettings get() = toolShelf.tools.first { it.id == "shape-default" }
    private val straightenTick = object : Runnable {
        override fun run() {
            val pointerId = pressurePointerId ?: return
            if (!straighten.active || straighten.snapped) return
            if (!pressureEraser.isErasing && !(pressureEraser.isArmed && lastPressure >= pressureEraser.pressureThreshold) &&
                shapeSettings.holdToStraightenEnabled && straighten.trySnap(SystemClock.uptimeMillis(), shapeSettings.straightenDelayMillis)) {
                activeStrokes.remove(pointerId)?.let(wetInkView::cancelStroke)
                mainHandler.removeCallbacks(activatePressureEraser)
                pressureEraser.cancelCandidate()
                updateShapePreview(ShapeKind.LINE)
            } else mainHandler.postDelayed(this, 30L)
        }
    }
    private val viewport = ViewportState()
    private val pressureEraser = PressureEraserController()
    private val shortcutDispatcher = XiaomiStylusShortcutDispatcher()
    private var keyboardShortcutSettings = KeyboardShortcutSettings()
    private var xiaomiPenShortcutSettings = XiaomiPenShortcutSettings()
    private val xiaomiGestureInterpreter = XiaomiStylusGestureInterpreter()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val transformGestureArbiter by lazy {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        TransformGestureArbiter(panSlop = touchSlop, scaleSlop = touchSlop * 2f)
    }
    private val toolShelf = ToolShelf()
    private var notebookLibrary = NotebookLibrary.sample()
    private val splitViewState = SplitViewState()
    private val pageSessions = mutableMapOf<PageSessionKey, PageSessionState>()
    private val fullPageToolbarConfig by lazy { FullPageToolbarConfig(loadFullPageToolbarIds()) }
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val workspace: NotebookWorkspace
        get() = notebookLibrary.activeNotebook()

    private lateinit var wetInkView: InProgressStrokesView
    private lateinit var dryInkView: DryInkView
    private lateinit var canvasBackgroundLayer: CanvasBackgroundLayer
    private lateinit var predictor: MotionEventPredictor
    private lateinit var currentBrush: Brush
    private lateinit var toolShelfRow: LinearLayout
    private lateinit var floatingToolShelfRow: LinearLayout
    private lateinit var toolDetailRow: LinearLayout
    private var toolDetailPopup: PopupWindow? = null
    private var darkTooltipPopup: PopupWindow? = null
    private var darkTooltipAnchor: View? = null
    private var darkTooltipLabel: CharSequence = ""
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
    private lateinit var pdfPageImporter: PdfPageImporter
    private lateinit var selectionOverlay: SelectionOverlayView
    private lateinit var paperContainer: CanvasInputHost
    private lateinit var splitCanvasHost: LinearLayout
    private lateinit var leftPageHost: FrameLayout
    private lateinit var rightPageHost: FrameLayout
    private lateinit var splitDivider: View
    private lateinit var pageSurfaceBackground: PageSurfaceView
    private lateinit var pageSurfaceForeground: PageSurfaceView
    private lateinit var sectionPaneToggle: View
    private lateinit var pagePaneToggle: View
    private lateinit var toolbarToggle: View
    private lateinit var fullPageToggle: View
    private lateinit var fullPageToggleIcon: ImageView
    private lateinit var fullPagePaneButtons: View
    private lateinit var fullPageSectionPaneToggle: View
    private lateinit var fullPagePagePaneToggle: View
    private var fullViewButton: View? = null
    private var normalViewButton: View? = null
    private var pageTitleViewButton: View? = null
    private var backgroundViewButton: View? = null
    private var zoomPercentButton: TextView? = null
    private var pageColorSwatch: View? = null
    private var zoomLockButton: View? = null
    private var zoomInButton: View? = null
    private var zoomOutButton: View? = null
    private var zoomResetViewButton: View? = null
    private var pageWidthViewButton: View? = null
    private var splitViewButton: View? = null

    private var predictionEnabled = true
    private var transformGestureActive = false
    private var lastGestureFocusX = 0f
    private var lastGestureFocusY = 0f
    private var pressurePointerId: Int? = null
    private var lastPressure = 0f
    private var lastPressurePoint = PointF()
    private var activeDirectTool: DrawingToolKind? = null
    private var lastPenToolId: String = toolShelf.tools.first { it.kind == DrawingToolKind.PEN }.id
    private var singleFingerPanEnabled = false
    private var panoramaSensitivity = 1f
    private var springLoadedPanorama = false
    private var springLoadedPanoramaRestore = false
    private var springLoadedInsertSpaceRestore = false
    private var insertSpaceModeEnabled = false
    private var insertSpaceActive = false
    private var insertSpacePointerId: Int? = null
    private var insertSpaceStartDocY = 0f
    private var insertSpaceCurrentDocY = 0f
    private var directPageGestureCaptured = false
    private var mouseCanvasGesture = CanvasDragIntent.NONE
    private var lastMouseGestureY = 0f
    private var mouseSecondaryGesture = MouseSecondaryGesture.NONE
    private var mouseSecondaryStart = PointF()
    private val mouseSecondarySlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private var lastStylusCanvasPoint: PointF? = null
    private var xiaomiSecondaryKey: XiaomiStylusKey? = null
    private var xiaomiSecondaryDownAt = 0L
    private var xiaomiSecondaryPointerId: Int? = null
    private var xiaomiSecondaryPreviewActive = false
    private var xiaomiSecondaryCommitted = false
    private var sectionPaneVisible = true
    private var pagePaneVisible = true
    private var drawingToolbarVisible = true
    private var fullPageMode = false
    private var fullPageSectionPaneOpen = false
    private var fullPagePagePaneOpen = false
    private var activeRibbonPage = RibbonPage.DRAW
    private var darkCanvasActive = true
    private var legacyBackgroundFormat = CanvasBackgroundFormat.GRID_STANDARD
    private var zoomLocked = false
    private var lastPageTemplateSettings = PageViewSettings()
    private var imageImportDisplayMode = ImageImportDisplayMode.ASK
    private var pdfPrintoutDisplayMode = ImageImportDisplayMode.ASK
    private var pendingExport: PendingExport? = null
    private var pendingSectionExport: SectionExportTarget? = null
    private var pendingPdfPageTarget: PdfPageTarget? = null
    private var boundPageKey: PageSessionKey? = null
    private var splitFocusVisible = false
    private var navigationDropTarget: NavigationDropTarget? = null
    private var pageNavigationClipboard: PageNavigationClipboard? = null
    private var insertObjectClipboard: InsertObjectClipboard? = null
    private var pendingImageSaveFile: File? = null
    private val toggledPreviousTools = mutableMapOf<XiaomiStylusKey, String>()
    private val heldPreviousTools = mutableMapOf<XiaomiStylusKey, String>()
    private val consumedKeyboardShortcutKeys = mutableSetOf<Pair<Int, Int>>()
    private var panoramaPointerId: Int? = null

    private val hideSplitFocus =
        Runnable {
            splitFocusVisible = false
            updateSplitFocusVisuals()
        }

    private val showDarkTooltip =
        Runnable {
            val anchor = darkTooltipAnchor ?: return@Runnable
            if (!anchor.isShown || !anchor.isHovered || darkTooltipLabel.isEmpty()) return@Runnable
            val label =
                TextView(this).apply {
                    text = darkTooltipLabel
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = rounded(DARK_TOOLTIP_BACKGROUND, dp(4).toFloat(), DARK_TOOLTIP_BACKGROUND)
                }
            darkTooltipPopup =
                PopupWindow(label, WRAP, WRAP, false).apply {
                    isOutsideTouchable = false
                    isClippingEnabled = true
                    elevation = dp(8).toFloat()
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    showAsDropDown(anchor, 0, dp(2))
                }
        }

    private val activatePressureEraser =
        Runnable {
            val pointerId = pressurePointerId ?: return@Runnable
            if (!pressureEraser.activate(SystemClock.uptimeMillis())) return@Runnable
            activeStrokes.remove(pointerId)?.let(wetInkView::cancelStroke)
            cancelShapeGesture()
            renderToolShelf()
            renderToolDetails()
            eraseAt(lastPressurePoint, pressureEraserRadius())
        }

    private val dispatchXiaomiGestureDeadline =
        Runnable {
            applyXiaomiGestureDecisions(xiaomiGestureInterpreter.advance(SystemClock.uptimeMillis()))
            scheduleXiaomiGestureDeadline()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = TITLE_PURPLE
        window.navigationBarColor = NAV_BACKGROUND
        loadPreferences()
        shortcutDispatcher.handler = StylusShortcutHandler(::handleXiaomiStylusSignal)
        pageContentRepository = PageContentRepository(filesDir)
        assetStore = AssetStore(this, pageContentRepository)
        pdfPageImporter = PdfPageImporter(pageContentRepository, assetStore)
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

    override fun onDestroy() {
        dismissDarkTooltip()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQUEST_PDF_PAGES) pendingPdfPageTarget = null
            if (requestCode == REQUEST_SAVE_IMAGE) pendingImageSaveFile = null
            return
        }
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
            REQUEST_EXPORT -> pendingExport?.let { type -> exportToUri(data.data ?: return, type) }.also {
                pendingExport = null
                pendingSectionExport = null
            }
            REQUEST_PDF_PAGES -> uris.firstOrNull()?.let(::importPdfPages)
            REQUEST_SAVE_IMAGE -> data.data?.let(::finishSaveImageAs)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (shortcutDispatcher.dispatch(event)) return true
        val keyIdentity = event.deviceId to event.keyCode
        if (event.action == KeyEvent.ACTION_UP && consumedKeyboardShortcutKeys.remove(keyIdentity)) return true
        if (
            currentFocus !is EditText &&
            DirectInputPolicy.isSelectionDeleteKey(event.keyCode, event.action, event.repeatCount, !event.hasNoModifiers())
        ) {
            if (deleteContentSelection() > 0) {
                consumedKeyboardShortcutKeys += keyIdentity
                return true
            }
        }
        val chord = ShortcutKeyChord.fromEvent(event)
        if (chord != null) {
            keyboardShortcutSettings.actionFor(chord)?.let { actionId ->
                if (event.repeatCount == 0 && executeShortcutAction(actionId)) {
                    consumedKeyboardShortcutKeys += keyIdentity
                    return true
                }
                if (keyIdentity in consumedKeyboardShortcutKeys) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            ::paperContainer.isInitialized
        ) {
            val location = IntArray(2)
            paperContainer.getLocationOnScreen(location)
            val localX = event.rawX - location[0]
            val localY = event.rawY - location[1]
            if (localX in 0f..paperContainer.width.toFloat() && localY in 0f..paperContainer.height.toFloat()) {
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val decision =
                    CanvasInputRouting.wheelDecision(
                        vertical = vertical,
                        horizontal = horizontal,
                        shiftPressed = event.hasShiftModifier(),
                        ctrlPressed = event.hasCtrlModifier(),
                    )
                when (decision.intent) {
                    WheelIntent.ZOOM -> viewport.applyGesture(localX, localY, localX, localY, 1.12f.pow(decision.amount))
                    WheelIntent.PAN_HORIZONTAL -> viewport.applyGesture(0f, 0f, decision.amount * dp(48), 0f, 1f)
                    WheelIntent.PAN_VERTICAL -> viewport.applyGesture(0f, 0f, 0f, decision.amount * dp(48), 1f)
                    WheelIntent.NONE -> return super.dispatchGenericMotionEvent(event)
                }
                applyViewportToLayers()
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        cancelShapeGesture()
        dismissDarkTooltip()
        toolDetailPopup?.dismiss()
        toolDetailPopup = null
        mainHandler.removeCallbacks(activatePressureEraser)
        mainHandler.removeCallbacks(hideSplitFocus)
        mainHandler.removeCallbacks(dispatchXiaomiGestureDeadline)
        consumedKeyboardShortcutKeys.clear()
        applyXiaomiGestureDecisions(xiaomiGestureInterpreter.reset())
        pressureEraser.cancelCandidate()
        pressurePointerId = null
        activeDirectTool = null
        panoramaPointerId = null
        directPageGestureCaptured = false
        mouseCanvasGesture = CanvasDragIntent.NONE
        resetMouseSecondaryGesture(cancelPreview = true)
        resetXiaomiSecondaryGesture(cancelPreview = true)
        transformGestureActive = false
        dryInkView.setEraserCursor(null)
        clearContentSelection()
        if (::textInputLayer.isInitialized) {
            textInputLayer.endEditing()
            textInputLayer.abandonTemporaryEditors()
        }
        if (::tagLayer.isInitialized) tagLayer.cancelPlacement()
        wetInkView.cancelUnfinishedStrokes()
        activeStrokes.clear()
        super.onPause()
    }

    private fun handleXiaomiStylusSignal(signal: StylusShortcutSignal): Boolean {
        if (xiaomiProfileUsesSecondaryLasso(signal.key)) return handleXiaomiSecondarySignal(signal)
        mainHandler.removeCallbacks(dispatchXiaomiGestureDeadline)
        xiaomiGestureInterpreter.doubleTapMillis = xiaomiPenShortcutSettings.doubleTapMillis
        xiaomiGestureInterpreter.holdMillis = xiaomiPenShortcutSettings.holdMillis
        applyXiaomiGestureDecisions(xiaomiGestureInterpreter.acceptAt(signal, signal.eventTime))
        scheduleXiaomiGestureDeadline()
        return true
    }

    private fun xiaomiProfileUsesSecondaryLasso(key: XiaomiStylusKey): Boolean {
        val binding = xiaomiPenShortcutSettings.profile(key).single
        if (binding.type == StylusBindingType.LASSO_CONTEXT) return true
        if (binding.type != StylusBindingType.TOGGLE_TOOL) return false
        return toolShelf.tools.firstOrNull { it.id == binding.toolId }?.kind == DrawingToolKind.LASSO
    }

    private fun handleXiaomiSecondarySignal(signal: StylusShortcutSignal): Boolean {
        when (signal.phase) {
            StylusKeyPhase.DOWN -> {
                if (signal.repeatCount > 0) return true
                if (xiaomiSecondaryKey != null) resetXiaomiSecondaryGesture(cancelPreview = true)
                MojianCommandMenu.dismissActive()
                xiaomiSecondaryKey = signal.key
                xiaomiSecondaryDownAt = signal.eventTime
                xiaomiSecondaryCommitted = false

                val activePointer = pressurePointerId
                val activePoint = lastStylusCanvasPoint?.let { point -> PointF(point.x, point.y) }
                if (activePointer != null && activePoint != null) {
                    cancelStylusAction()
                    dryInkView.cancelLassoPreview()
                    xiaomiSecondaryPointerId = activePointer
                    beginXiaomiSecondaryPreview(activePoint.x, activePoint.y)
                }
            }
            StylusKeyPhase.UP -> {
                if (xiaomiSecondaryKey != signal.key) return true
                val heldMillis = maxOf(signal.heldMillis, signal.eventTime - xiaomiSecondaryDownAt)
                when (CanvasInputRouting.stylusShortcutReleaseIntent(heldMillis, xiaomiPenShortcutSettings.holdMillis)) {
                    SecondaryReleaseIntent.COMMIT_LASSO -> {
                        if (xiaomiSecondaryPreviewActive && !xiaomiSecondaryCommitted) {
                            finishContentLasso()
                            xiaomiSecondaryPreviewActive = false
                            xiaomiSecondaryCommitted = true
                        }
                    }
                    SecondaryReleaseIntent.OPEN_CONTEXT_MENU -> {
                        if (!xiaomiSecondaryCommitted) {
                            if (xiaomiSecondaryPreviewActive) dryInkView.cancelLassoPreview()
                            xiaomiSecondaryPreviewActive = false
                            val point = lastStylusCanvasPoint ?: PointF(paperContainer.width / 2f, paperContainer.height / 2f)
                            showCanvasContextMenuAt(point.x, point.y)
                        }
                    }
                }
                resetXiaomiSecondaryGesture(cancelPreview = false)
            }
        }
        return true
    }

    private fun resetXiaomiSecondaryGesture(cancelPreview: Boolean) {
        if (cancelPreview && xiaomiSecondaryPreviewActive && ::dryInkView.isInitialized) dryInkView.cancelLassoPreview()
        xiaomiSecondaryKey = null
        xiaomiSecondaryDownAt = 0L
        xiaomiSecondaryPointerId = null
        xiaomiSecondaryPreviewActive = false
        xiaomiSecondaryCommitted = false
    }

    private fun scheduleXiaomiGestureDeadline() {
        mainHandler.removeCallbacks(dispatchXiaomiGestureDeadline)
        val deadline = xiaomiGestureInterpreter.nextDeadline() ?: return
        mainHandler.postDelayed(dispatchXiaomiGestureDeadline, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun applyXiaomiGestureDecisions(decisions: List<StylusGestureDecision>) {
        decisions.forEach { decision ->
            val profile = xiaomiPenShortcutSettings.profile(decision.key)
            when (decision.kind) {
                StylusGestureKind.SINGLE_TAP -> executeStylusBinding(decision.key, profile.single)
                StylusGestureKind.DOUBLE_TAP -> {
                    if (profile.double.type == StylusBindingType.NONE) {
                        executeStylusBinding(decision.key, profile.single)
                        executeStylusBinding(decision.key, profile.single)
                    } else {
                        executeStylusBinding(decision.key, profile.double)
                    }
                }
                StylusGestureKind.HOLD_START -> {
                    val binding = profile.hold
                    if (binding.type == StylusBindingType.HOLD_TOOL) {
                        val target = binding.toolId ?: return@forEach
                        heldPreviousTools[decision.key] = toolShelf.selectedId
                        selectTool(target)
                    }
                }
                StylusGestureKind.HOLD_END -> {
                    val previous = heldPreviousTools.remove(decision.key)
                    if (previous != null && toolShelf.tools.any { it.id == previous }) selectTool(previous)
                    else if (profile.hold.type == StylusBindingType.NONE) executeStylusBinding(decision.key, profile.single)
                }
            }
        }
    }

    private fun executeStylusBinding(key: XiaomiStylusKey, binding: StylusActionBinding) {
        when (binding.type) {
            StylusBindingType.NONE,
            StylusBindingType.LASSO_CONTEXT,
            StylusBindingType.HOLD_TOOL -> Unit
            StylusBindingType.TOGGLE_TOOL -> {
                val target = binding.toolId ?: return
                val previous = toggledPreviousTools[key]
                if (toolShelf.selectedId == target && previous != null && toolShelf.tools.any { it.id == previous }) {
                    toggledPreviousTools.remove(key)
                    selectTool(previous)
                } else {
                    toggledPreviousTools[key] = toolShelf.selectedId
                    selectTool(target)
                }
            }
            StylusBindingType.UNDO -> dryInkView.undo()
            StylusBindingType.REDO -> dryInkView.redo()
            StylusBindingType.NEXT_TOOL -> cycleDrawingTool(1)
            StylusBindingType.PREVIOUS_TOOL -> cycleDrawingTool(-1)
        }
    }

    private fun drawingRibbonTools(): List<ToolPreset> =
        toolShelf.tools.sortedWith(
            compareBy {
                when (it.kind) {
                    DrawingToolKind.SHAPE -> 4
                    DrawingToolKind.LASSO -> 0
                    DrawingToolKind.ERASER -> 1
                    DrawingToolKind.HIGHLIGHTER -> 2
                    DrawingToolKind.PEN -> 3
                }
            },
        )

    private fun cycleDrawingTool(direction: Int) {
        val tools = drawingRibbonTools()
        if (tools.isEmpty()) return
        val current = tools.indexOfFirst { it.id == toolShelf.selectedId }.takeIf { it >= 0 } ?: 0
        val next = (current + direction).mod(tools.size)
        selectTool(tools[next].id)
    }

    private fun cyclePage(direction: Int) {
        val pages = workspace.pagePaneRows().filterIsInstance<PagePaneRow.PageItem>()
        if (pages.isEmpty()) return
        val current = pages.indexOfFirst { it.page.id == workspace.selectedPageId }.takeIf { it >= 0 } ?: 0
        val next = (current + direction).mod(pages.size)
        workspace.selectPage(pages[next].sectionId, pages[next].page.id)
        persistWorkspacePreferences()
        renderWorkspace()
    }

    private fun togglePanoramaMode() {
        cancelStylusAction()
        tagLayer.cancelPlacement()
        singleFingerPanEnabled = !singleFingerPanEnabled
        insertSpaceModeEnabled = false
        insertSpaceActive = false
        insertSpacePointerId = null
        panoramaPointerId = null
        dryInkView.setInsertSpaceHoverY(null)
        dryInkView.setInsertSpaceGuide(null, null)
        transformGestureActive = false
        toolDetailPopup?.dismiss()
        toolDetailPopup = null
        renderToolShelf()
    }

    private fun handlePanoramaToolClick(anchor: View) {
        if (!singleFingerPanEnabled) {
            togglePanoramaMode()
            return
        }
        showPanoramaDetails(anchor)
    }

    private fun beginSpringLoadedPanorama() {
        if (springLoadedPanorama) return
        springLoadedPanorama = true
        springLoadedPanoramaRestore = singleFingerPanEnabled
        springLoadedInsertSpaceRestore = insertSpaceModeEnabled
        singleFingerPanEnabled = true
        insertSpaceModeEnabled = false
        renderToolShelf()
    }

    private fun endSpringLoadedPanorama() {
        if (!springLoadedPanorama) return
        springLoadedPanorama = false
        singleFingerPanEnabled = springLoadedPanoramaRestore
        insertSpaceModeEnabled = springLoadedInsertSpaceRestore
        renderToolShelf()
    }

    private fun executeShortcutAction(actionId: String): Boolean {
        if (!::dryInkView.isInitialized) return false
        if (actionId.startsWith("tool:")) {
            val id = actionId.removePrefix("tool:")
            if (toolShelf.tools.none { it.id == id }) return false
            selectTool(id)
            return true
        }
        if (actionId.startsWith("home:")) {
            when (actionId) {
                "home:tag-important" -> beginTagPlacement(CanvasTagKind.IMPORTANT)
                "home:tag-question" -> beginTagPlacement(CanvasTagKind.QUESTION)
                else -> {
                    val command = HomeCommand.fromId(actionId.removePrefix("home:")) ?: return false
                    performHomeCommand(command, drawingRibbon)
                }
            }
            return true
        }
        if (actionId.startsWith("draw:background:")) {
            val format = runCatching { CanvasBackgroundFormat.valueOf(actionId.removePrefix("draw:background:")) }.getOrNull() ?: return false
            updateCurrentPageView { it.backgroundFormat = format }
            return true
        }
        if (actionId.startsWith("view:zoom:")) {
            val percent = actionId.removePrefix("view:zoom:").toIntOrNull() ?: return false
            setZoomScale(percent / 100f)
            return true
        }
        return when (actionId) {
            "file:backstage" -> { showBackstage(); true }
            "file:save" -> { saveBoundPageSession(); persistWorkspacePreferences(); Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show(); true }
            "file:export-page-pdf" -> { startExport(PendingExport.PDF); true }
            "file:export-notebook" -> { startExport(PendingExport.PRIVATE_PACKAGE); true }
            "insert:table-2x2" -> { insertTable(2, 2); true }
            "insert:attachment" -> { launchDocumentPicker(REQUEST_ATTACHMENT, "*/*"); true }
            "insert:pdf-printout" -> { launchDocumentPicker(REQUEST_PDF_PRINTOUT, "application/pdf"); true }
            "insert:spreadsheet" -> { launchSpreadsheetPicker(); true }
            "insert:images" -> { launchImagePicker(REQUEST_IMAGES); true }
            "insert:stickers" -> { showStickerPicker(); true }
            "insert:note-link" -> { showNoteLinkPicker(); true }
            "draw:undo" -> { clearContentSelection(); dryInkView.undo(); true }
            "draw:redo" -> { clearContentSelection(); dryInkView.redo(); true }
            "draw:previous-tool" -> { cycleDrawingTool(-1); true }
            "draw:next-tool" -> { cycleDrawingTool(1); true }
            "draw:pan" -> { togglePanoramaMode(); true }
            "draw:insert-space" -> { handleInsertSpace(drawingRibbon); true }
            "draw:delete-selection" -> { deleteContentSelection(); true }
            "draw:clear" -> { confirmClearPage(); true }
            "view:full-page" -> { setFullPageMode(true); true }
            "view:normal" -> { setFullPageMode(false); true }
            "view:split" -> { toggleSplitView(); true }
            "view:zoom-lock" -> { zoomLocked = !zoomLocked; persistWorkspacePreferences(); refreshViewRibbonState(); true }
            "view:zoom-in" -> { zoomByStep(1); true }
            "view:zoom-out" -> { zoomByStep(-1); true }
            "view:zoom-reset" -> { setZoomScale(1f); true }
            "view:fit-width" -> { fitPageWidth(); true }
            "view:toggle-title" -> { updateCurrentPageView { it.pageTitleVisible = !it.pageTitleVisible }; true }
            "view:toggle-theme" -> { updateCurrentPageView { it.darkBackground = !it.darkBackground }; true }
            "view:paper:A4" -> { updateCurrentPageView { it.paperSize = PaperSize.A4; it.a4PageCount = it.a4PageCount.coerceAtLeast(1) }; true }
            "view:paper:BOUNDLESS" -> { updateCurrentPageView { it.paperSize = PaperSize.BOUNDLESS }; true }
            "view:section-pane" -> { toggleSectionPane(); true }
            "view:page-pane" -> { togglePagePane(); true }
            "view:previous-page" -> { cyclePage(-1); true }
            "view:next-page" -> { cyclePage(1); true }
            else -> false
        }
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
        workArea.addView(buildSplitCanvasHost(), LinearLayout.LayoutParams(0, MATCH, 1f))
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
                    addView(
                        backstageNavItem("快捷键", false) { dialog.setContentView(buildShortcutOptionsPage(dialog)) },
                        LinearLayout.LayoutParams(MATCH, dp(40)),
                    )
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
        prefs.getString(KEY_NOTEBOOK_LIBRARY, null)
            ?.takeIf(String::isNotBlank)
            ?.let(NotebookLibrary::fromJson)
            ?.let { notebookLibrary = it }
        predictionEnabled = prefs.getBoolean(KEY_PREDICTION_ENABLED, true)
        imageImportDisplayMode =
            runCatching { ImageImportDisplayMode.valueOf(prefs.getString(KEY_IMAGE_IMPORT_DISPLAY_MODE, "") ?: "") }
                .getOrDefault(ImageImportDisplayMode.ASK)
        pdfPrintoutDisplayMode =
            runCatching { ImageImportDisplayMode.valueOf(prefs.getString(KEY_PDF_PRINTOUT_DISPLAY_MODE, "") ?: "") }
                .getOrDefault(ImageImportDisplayMode.ASK)
        sectionPaneVisible = prefs.getBoolean(KEY_SECTION_PANE_VISIBLE, true)
        pagePaneVisible = prefs.getBoolean(KEY_PAGE_PANE_VISIBLE, true)
        panoramaSensitivity = prefs.getFloat(KEY_PANORAMA_SENSITIVITY, 1f).coerceIn(MINIMUM_PANORAMA_SENSITIVITY, MAXIMUM_PANORAMA_SENSITIVITY)
        zoomLocked = prefs.getBoolean(KEY_ZOOM_LOCKED, false)

        val modeStr = prefs.getString(KEY_PAGE_PANE_MODE, PagePaneMode.CURRENT_SECTION.name)
        val mode = runCatching { PagePaneMode.valueOf(modeStr ?: "") }.getOrDefault(PagePaneMode.CURRENT_SECTION)
        notebookLibrary.setPagePaneMode(mode)

        val shelfJson = prefs.getString(KEY_TOOL_SHELF, "")
        if (!shelfJson.isNullOrBlank()) {
            ToolShelf.fromJson(shelfJson)?.let { loadedShelf ->
                toolShelf.restoreFrom(loadedShelf)
            }
            val oldShelfVersion = runCatching { JSONObject(shelfJson).optInt("version", 1) }.getOrDefault(1)
            if (oldShelfVersion < 2) {
                toolShelf.pressureEraser.apply {
                    pressureEraseThresholdPercent = (prefs.getFloat(KEY_PRESSURE_THRESHOLD, 0.78f) * 100).roundToInt().coerceIn(40, 100)
                    pressureEraseDwellMillis = prefs.getLong(KEY_PRESSURE_DWELL_MILLIS, 140L).coerceIn(50L, 500L)
                }
                prefs.edit().putString(KEY_TOOL_SHELF, toolShelf.toJson()).apply()
            }
        }
        loadRemainingPreferences(prefs)
    }

    private fun buildShortcutOptionsPage(dialog: Dialog): View =
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
                    addView(backstageNavItem("常规", false) { dialog.setContentView(buildOptionsPage(dialog)) }, LinearLayout.LayoutParams(MATCH, dp(40)))
                    addView(backstageNavItem("快捷键", true) {}, LinearLayout.LayoutParams(MATCH, dp(40)))
                    for (label in listOf("显示", "同步", "保存和备份", "语言", "高级")) {
                        addView(backstageNavItem(label, false) { showShell("$label 选项") }, LinearLayout.LayoutParams(MATCH, dp(40)))
                    }
                },
                LinearLayout.LayoutParams(dp(150), MATCH),
            )

            val body =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(30), dp(22), dp(34), dp(32))
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "快捷键"
                            setTextColor(TEXT_PRIMARY)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        },
                        LinearLayout.LayoutParams(MATCH, dp(54)),
                    )
                    addView(optionsSectionTitle("触控笔"), LinearLayout.LayoutParams(MATCH, dp(36)))
                    addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)))
                    addView(
                        shortcutTimingRow("双击间隔", 150, 600, xiaomiPenShortcutSettings.doubleTapMillis.toInt()) { value ->
                            xiaomiPenShortcutSettings.doubleTapMillis = value.toLong()
                            xiaomiGestureInterpreter.doubleTapMillis = value.toLong()
                            persistWorkspacePreferences()
                        },
                        LinearLayout.LayoutParams(MATCH, dp(64)),
                    )
                    addView(
                        shortcutTimingRow("按住阈值", 200, 1_200, xiaomiPenShortcutSettings.holdMillis.toInt()) { value ->
                            xiaomiPenShortcutSettings.holdMillis = value.toLong()
                            xiaomiGestureInterpreter.holdMillis = value.toLong()
                            persistWorkspacePreferences()
                        },
                        LinearLayout.LayoutParams(MATCH, dp(64)),
                    )
                    XiaomiStylusKey.entries.forEach { key ->
                        val profile = xiaomiPenShortcutSettings.profile(key)
                        val label = if (key == XiaomiStylusKey.PAGE_UP) "PageUp" else "PageDown"
                        addView(optionsSectionTitle(label), LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(8) })
                        addView(stylusBindingSettingsRow("单击", profile.single, holdOnly = false, allowLassoContext = true, dialog), LinearLayout.LayoutParams(MATCH, dp(46)))
                        addView(stylusBindingSettingsRow("双击", profile.double, holdOnly = false, allowLassoContext = false, dialog), LinearLayout.LayoutParams(MATCH, dp(46)))
                        addView(stylusBindingSettingsRow("按住", profile.hold, holdOnly = true, allowLassoContext = false, dialog), LinearLayout.LayoutParams(MATCH, dp(46)))
                    }

                    addView(optionsSectionTitle("键盘"), LinearLayout.LayoutParams(MATCH, dp(54)).apply { topMargin = dp(16) })
                    addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)))
                    ShortcutCategory.entries.forEach { category ->
                        val actions = ShortcutActionCatalog.actions(toolShelf.tools).filter { it.category == category }
                        if (actions.isEmpty()) return@forEach
                        addView(optionsSectionTitle(category.label), LinearLayout.LayoutParams(MATCH, dp(38)))
                        actions.forEach { descriptor ->
                            addView(shortcutBindingRow(descriptor, dialog), LinearLayout.LayoutParams(MATCH, dp(52)))
                        }
                    }
                }
            addView(
                ScrollView(this@MainActivity).apply { isFillViewport = true; addView(body, ViewGroup.LayoutParams(MATCH, WRAP)) },
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
        }

    private fun shortcutTimingRow(title: String, minimum: Int, maximum: Int, current: Int, onChanged: (Int) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val valueLabel =
                TextView(this@MainActivity).apply {
                    text = "$current ms"
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    setTextColor(ACCENT_PURPLE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                },
                LinearLayout.LayoutParams(dp(100), MATCH),
            )
            addView(
                SeekBar(this@MainActivity).apply {
                    max = maximum - minimum
                    progress = current.coerceIn(minimum, maximum) - minimum
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                                val value = minimum + progress
                                valueLabel.text = "$value ms"
                                if (fromUser) onChanged(value)
                            }
                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                        },
                    )
                },
                LinearLayout.LayoutParams(0, dp(42), 1f),
            )
            addView(valueLabel, LinearLayout.LayoutParams(dp(82), MATCH))
        }

    private fun stylusBindingSettingsRow(
        title: String,
        binding: StylusActionBinding,
        holdOnly: Boolean,
        allowLassoContext: Boolean,
        dialog: Dialog,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                },
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
            addView(
                settingsValueButton(stylusBindingLabel(binding)).apply {
                    setOnClickListener {
                        showStylusBindingChooser(this, binding, holdOnly, allowLassoContext) { dialog.setContentView(buildShortcutOptionsPage(dialog)) }
                    }
                },
                LinearLayout.LayoutParams(dp(170), dp(36)),
            )
        }

    private fun stylusBindingLabel(binding: StylusActionBinding): String =
        when (binding.type) {
            StylusBindingType.TOGGLE_TOOL -> {
                val tool = toolShelf.tools.firstOrNull { it.id == binding.toolId }
                if (tool?.kind == DrawingToolKind.LASSO) "套索（短按菜单）" else tool?.displayName ?: binding.type.label
            }
            StylusBindingType.HOLD_TOOL -> toolShelf.tools.firstOrNull { it.id == binding.toolId }?.displayName ?: binding.type.label
            else -> binding.type.label
        }

    private fun showStylusBindingChooser(
        anchor: View,
        binding: StylusActionBinding,
        holdOnly: Boolean,
        allowLassoContext: Boolean,
        refresh: () -> Unit,
    ) {
        val types =
            if (holdOnly) listOf(StylusBindingType.NONE, StylusBindingType.HOLD_TOOL)
            else buildList {
                add(StylusBindingType.NONE)
                add(StylusBindingType.TOGGLE_TOOL)
                if (allowLassoContext) add(StylusBindingType.LASSO_CONTEXT)
                add(StylusBindingType.UNDO)
                add(StylusBindingType.REDO)
                add(StylusBindingType.NEXT_TOOL)
                add(StylusBindingType.PREVIOUS_TOOL)
            }
        val tools = drawingRibbonTools()
        val items = types.map { type ->
            val toolSubmenu =
                if (type == StylusBindingType.TOGGLE_TOOL || type == StylusBindingType.HOLD_TOOL) {
                    tools.map { tool ->
                        MojianCommandMenu.Item(
                            label = tool.displayName,
                            icon = when (tool.kind) {
                                DrawingToolKind.PEN -> "pen"
                                DrawingToolKind.HIGHLIGHTER -> "highlight"
                                DrawingToolKind.ERASER -> "eraser"
                                DrawingToolKind.SHAPE -> "shapes"
                        DrawingToolKind.LASSO -> "lasso"
                            },
                            checked = binding.type == type && binding.toolId == tool.id,
                            action = {
                                binding.type = type
                                binding.toolId = tool.id
                                persistWorkspacePreferences()
                                refresh()
                            },
                        )
                    }
                } else emptyList()
            MojianCommandMenu.Item(
                label = type.label,
                icon = when (type) {
                    StylusBindingType.NONE -> "eye_off"
                    StylusBindingType.TOGGLE_TOOL, StylusBindingType.HOLD_TOOL -> "pen"
                    StylusBindingType.LASSO_CONTEXT -> "lasso"
                    StylusBindingType.UNDO, StylusBindingType.PREVIOUS_TOOL -> "arrow_undo"
                    StylusBindingType.REDO, StylusBindingType.NEXT_TOOL -> "arrow_redo"
                },
                checked = binding.type == type,
                submenu = toolSubmenu,
                action = if (toolSubmenu.isEmpty()) {
                    {
                        binding.type = type
                        binding.toolId = null
                        persistWorkspacePreferences()
                        refresh()
                    }
                } else null,
            )
        }
        MojianCommandMenu.show(this, anchor, items, widthDp = 238)
    }

    private fun shortcutBindingRow(descriptor: ShortcutActionDescriptor, dialog: Dialog): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            addView(
                TextView(this@MainActivity).apply {
                    text = descriptor.label
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                },
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
            val chord = keyboardShortcutSettings.bindings[descriptor.id]
            addView(
                settingsValueButton(chord?.displayName ?: "未设置").apply {
                    setOnClickListener { showShortcutCaptureDialog(descriptor, dialog) }
                },
                LinearLayout.LayoutParams(dp(132), dp(36)),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "×"
                    gravity = Gravity.CENTER
                    setTextColor(if (chord == null) TEXT_SECONDARY else TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    isEnabled = chord != null
                    setOnClickListener {
                        keyboardShortcutSettings.clear(descriptor.id)
                        persistWorkspacePreferences()
                        dialog.setContentView(buildShortcutOptionsPage(dialog))
                    }
                },
                LinearLayout.LayoutParams(dp(42), dp(36)),
            )
        }

    private fun showShortcutCaptureDialog(descriptor: ShortcutActionDescriptor, owner: Dialog) {
        val capture = Dialog(this)
        capture.requestWindowFeature(Window.FEATURE_NO_TITLE)
        capture.setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(20), dp(16))
                background = rounded(TOOLBAR_BACKGROUND, dp(5).toFloat(), BORDER_STRONG)
                addView(
                    TextView(this@MainActivity).apply {
                        text = "设置 ${descriptor.label}"
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    },
                    LinearLayout.LayoutParams(MATCH, dp(38)),
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "按下新的组合键"
                        setTextColor(TEXT_SECONDARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(MATCH, dp(42)),
                )
                addView(
                    TextView(this@MainActivity).apply {
                        text = "取消"
                        gravity = Gravity.CENTER
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        background = rounded(Color.rgb(48, 48, 48), dp(3).toFloat(), BORDER_COLOR)
                        setOnClickListener { capture.dismiss() }
                    },
                    LinearLayout.LayoutParams(dp(78), dp(36)).apply { gravity = Gravity.END },
                )
            },
        )
        capture.setOnKeyListener { _, _, event ->
            if (event.device?.name == "Xiaomi Smart Pen Keyboard") {
                Toast.makeText(this, "触控笔按键请在上方设置", Toast.LENGTH_SHORT).show()
                return@setOnKeyListener true
            }
            val chord = ShortcutKeyChord.fromEvent(event) ?: return@setOnKeyListener false
            keyboardShortcutSettings.assign(descriptor.id, chord)
            persistWorkspacePreferences()
            capture.dismiss()
            owner.setContentView(buildShortcutOptionsPage(owner))
            true
        }
        capture.show()
        capture.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(420), WRAP)
        }
    }

    private fun loadRemainingPreferences(prefs: android.content.SharedPreferences) {
        keyboardShortcutSettings = KeyboardShortcutSettings.fromJson(prefs.getString(KEY_KEYBOARD_SHORTCUTS, null))
        xiaomiPenShortcutSettings =
            XiaomiPenShortcutSettings.fromJson(prefs.getString(KEY_XIAOMI_PEN_SHORTCUTS, null))
                .sanitized(toolShelf.tools.map(ToolPreset::id).toSet())
        xiaomiGestureInterpreter.doubleTapMillis = xiaomiPenShortcutSettings.doubleTapMillis
        xiaomiGestureInterpreter.holdMillis = xiaomiPenShortcutSettings.holdMillis
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
            if (focusedSectionId.isNotEmpty() && workspace.allSections().any { it.id == focusedSectionId }) {
                workspace.selectSection(focusedSectionId)
            } else {
                workspace.focusNotebook()
            }
        }
        val selectedPageId = prefs.getString(KEY_SELECTED_PAGE_ID, "")
        if (!selectedPageId.isNullOrEmpty()) {
            for (section in workspace.allSections()) {
                if (section.pages.any { it.id == selectedPageId }) {
                    workspace.selectPage(section.id, selectedPageId)
                    break
                }
            }
        }
        val savedBg = prefs.getString(KEY_BACKGROUND_FORMAT, CanvasBackgroundFormat.GRID_STANDARD.name)
        legacyBackgroundFormat =
            runCatching { CanvasBackgroundFormat.valueOf(savedBg ?: "") }
                .getOrDefault(CanvasBackgroundFormat.GRID_STANDARD)
        restorePageViewSettings(prefs.getString(KEY_PAGE_VIEW_SETTINGS, ""), legacyBackgroundFormat)
        lastPageTemplateSettings =
            prefs.getString(KEY_LAST_PAGE_TEMPLATE, "")
                ?.takeIf(String::isNotBlank)
                ?.let { runCatching { PageViewSettings.fromJson(JSONObject(it)) }.getOrNull() }
                ?.copy(a4PageCount = 1)
                ?: PageViewSettings(backgroundFormat = legacyBackgroundFormat)
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
            .putFloat(KEY_PANORAMA_SENSITIVITY, panoramaSensitivity)
            .putBoolean(KEY_ZOOM_LOCKED, zoomLocked)
            .putString(KEY_KEYBOARD_SHORTCUTS, keyboardShortcutSettings.toJson())
            .putString(KEY_XIAOMI_PEN_SHORTCUTS, xiaomiPenShortcutSettings.toJson())
            .putString(KEY_NOTEBOOK_LIBRARY, notebookLibrary.toJsonString())
            .putString(KEY_ACTIVE_NOTEBOOK_ID, notebookLibrary.activeNotebookId)
            .putString(KEY_FOCUSED_SECTION_ID, workspace.focusedSectionId.orEmpty())
            .putString(KEY_SELECTED_PAGE_ID, workspace.selectedPageId)
            .putString(KEY_BACKGROUND_FORMAT, dryInkView.backgroundFormat.name)
            .apply()
    }

    private fun restorePageViewSettings(
        jsonString: String?,
        legacyBackground: CanvasBackgroundFormat,
    ) {
        val root = jsonString?.takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() }
        for (notebook in notebookLibrary.notebooks) {
            for (section in notebook.allSections()) {
                for (page in section.pages) {
                    val json = root?.optJSONObject(page.id)
                    val restored =
                        if (json != null) PageViewSettings.fromJson(json)
                        else page.viewSettings.copy(backgroundFormat = legacyBackground)
                    page.viewSettings.paperSize = restored.paperSize
                    page.viewSettings.pageColor = restored.pageColor
                    page.viewSettings.backgroundFormat =
                        if (json?.has("backgroundFormat") == true) restored.backgroundFormat else legacyBackground
                    page.viewSettings.pageTitleVisible = restored.pageTitleVisible
                    page.viewSettings.darkBackground = restored.darkBackground
                    page.viewSettings.a4PageCount = restored.a4PageCount.coerceAtLeast(1)
                }
            }
        }
    }

    private fun persistPageViewSettings() {
        val root = JSONObject()
        for (notebook in notebookLibrary.notebooks) {
            for (section in notebook.allSections()) {
                for (page in section.pages) root.put(page.id, page.viewSettings.toJson())
            }
        }
        getSharedPreferences(OPTIONS_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(KEY_PAGE_VIEW_SETTINGS, root.toString())
            .apply()
    }

    private fun persistLastPageTemplate() {
        getSharedPreferences(OPTIONS_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PAGE_TEMPLATE, lastPageTemplateSettings.copy(a4PageCount = 1).toJson().toString())
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
            id in setOf("draw:undo", "draw:redo", "draw:add", "draw:pan", "draw:insert_space", "draw:rule_lines", "draw:clear") -> true
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
        row.addView(ribbonTab("视图", activeRibbonPage == RibbonPage.VIEW) { selectRibbonPage(RibbonPage.VIEW) })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    }

    private fun selectRibbonPage(page: RibbonPage) {
        if (activeRibbonPage == page) return
        activeRibbonPage = page
        populateRibbonTabs(ribbonTabs as LinearLayout)
        val host = drawingRibbon as FrameLayout
        host.removeAllViews()
        host.addView(
            when (page) {
                RibbonPage.HOME -> buildHomeRibbon()
                RibbonPage.INSERT -> buildInsertRibbon()
                RibbonPage.DRAW -> buildDrawingRibbon()
                RibbonPage.VIEW -> buildViewRibbon()
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        when (page) {
            RibbonPage.DRAW -> {
                renderToolShelf()
                renderToolDetails()
            }
            RibbonPage.VIEW -> refreshViewRibbonState()
            RibbonPage.HOME,
            RibbonPage.INSERT -> Unit
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
                    addView(insertCommandButton("table", "表格", action = ::showTablePicker))
                    addView(verticalDivider())
                    addView(insertCommandButton("attach", "文件附件") { launchDocumentPicker(REQUEST_ATTACHMENT, "*/*") })
                    addView(insertCommandButton("document_page", "文件打印样式") { launchDocumentPicker(REQUEST_PDF_PRINTOUT, "application/pdf") })
                    addView(insertCommandButton("table_simple", "电子表格", iconTint = Color.rgb(73, 170, 107)) { launchSpreadsheetPicker() })
                    addView(verticalDivider())
                    addView(insertCommandButton("image", "图片", iconTint = Color.rgb(86, 156, 214)) { launchImagePicker(REQUEST_IMAGES) })
                    addView(insertCommandButton("sticker", "贴纸") { showStickerPicker() })
                    addView(verticalDivider())
                    addView(insertCommandButton("link", "链接") { showNoteLinkPicker() })
                },
                ViewGroup.LayoutParams(WRAP, MATCH),
            )
        }

    private fun insertCommandButton(
        icon: String,
        label: String,
        iconTint: Int = TEXT_PRIMARY,
        action: (View) -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), 0, dp(7), 0)
            background = rounded(Color.TRANSPARENT, dp(2).toFloat(), Color.TRANSPARENT)
            addView(
                ImageView(this@MainActivity).apply {
                    setImageDrawable(fluentIcon(icon))
                    imageTintList = ColorStateList.valueOf(iconTint)
                },
                LinearLayout.LayoutParams(dp(22), dp(22)),
            )
            setOnClickListener { action(this) }
            contentDescription = label
            setDarkTooltip(this, label)
            layoutParams = LinearLayout.LayoutParams(dp(40), MATCH)
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

    private fun buildViewRibbon(): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(TOOLBAR_BACKGROUND)
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(2), dp(8), dp(2))

                    fullViewButton =
                        commandButton("full_screen_maximize", "整页视图", fullPageMode) { setFullPageMode(true) }
                    addView(fullViewButton, LinearLayout.LayoutParams(dp(40), dp(42)))
                    normalViewButton =
                        commandButton("panel_left", "普通视图", !fullPageMode) { setFullPageMode(false) }
                    addView(normalViewButton, LinearLayout.LayoutParams(dp(40), dp(42)))
                    splitViewButton =
                        commandButton("split_view", "拆分视图", splitViewState.enabled) { toggleSplitView() }
                    addView(splitViewButton, LinearLayout.LayoutParams(dp(40), dp(42)))
                    addView(verticalDivider())

                    zoomLockButton =
                        commandButton("lock_closed", "锁定显示比例", zoomLocked) {
                            zoomLocked = !zoomLocked
                            persistWorkspacePreferences()
                            refreshViewRibbonState()
                        }
                    addView(zoomLockButton, LinearLayout.LayoutParams(dp(38), dp(42)))
                    zoomPercentButton = zoomValueButton()
                    addView(zoomPercentButton, LinearLayout.LayoutParams(dp(70), dp(34)).apply { marginEnd = dp(3) })
                    zoomInButton = commandButton("zoom_in", "放大", false) { zoomByStep(1) }
                    addView(zoomInButton, LinearLayout.LayoutParams(dp(38), dp(42)))
                    zoomOutButton = commandButton("zoom_out", "缩小", false) { zoomByStep(-1) }
                    addView(zoomOutButton, LinearLayout.LayoutParams(dp(38), dp(42)))
                    zoomResetViewButton = zoomResetButton()
                    addView(zoomResetViewButton, LinearLayout.LayoutParams(dp(54), dp(34)).apply { marginStart = dp(2) })
                    pageWidthViewButton = commandButton("page_width", "页宽", false) { fitPageWidth() }
                    addView(pageWidthViewButton, LinearLayout.LayoutParams(dp(40), dp(42)))
                    addView(verticalDivider())

                    addView(
                        pageBoundCommand("rule_lines", "基准线") { anchor -> showBackgroundFormatMenu(anchor) },
                        LinearLayout.LayoutParams(dp(40), dp(42)),
                    )
                    addView(pageColorButton(), LinearLayout.LayoutParams(dp(40), dp(42)))
                    addView(
                        pageBoundCommand("document_page", "纸张大小") { anchor -> showPaperSizeMenu(anchor) },
                        LinearLayout.LayoutParams(dp(40), dp(42)),
                    )
                    pageTitleViewButton =
                        pageBoundCommand(
                            "eye_off",
                            "隐藏页面标题",
                            selected = workspace.selectedPage()?.viewSettings?.pageTitleVisible == false,
                        ) {
                            updateCurrentPageView { it.pageTitleVisible = !it.pageTitleVisible }
                        }
                    addView(pageTitleViewButton, LinearLayout.LayoutParams(dp(40), dp(42)))
                    backgroundViewButton =
                        pageBoundCommand(
                            "weather_moon",
                            "切换背景",
                            selected = workspace.selectedPage()?.viewSettings?.darkBackground == true,
                        ) {
                            updateCurrentPageView { it.darkBackground = !it.darkBackground }
                        }
                    addView(backgroundViewButton, LinearLayout.LayoutParams(dp(40), dp(42)))
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
            setDarkTooltip(this, command.label)
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
            setDarkTooltip(this, command.label)
        }

    private fun performHomeCommand(command: HomeCommand, anchor: View) {
        when (command) {
            HomeCommand.PASTE -> performPasteCommand()
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
        MojianCommandMenu.show(
            this,
            anchor,
            choices.map { (label, action) ->
                MojianCommandMenu.Item(label = label, icon = "style_guide") {
                    if (!action()) showTextSelectionHint()
                }
            },
            widthDp = 220,
        )
    }

    private fun showTagMenu(anchor: View) {
        MojianCommandMenu.show(
            this,
            anchor,
            CanvasTagKind.entries.map { kind ->
                MojianCommandMenu.Item(kind.label, icon = "tag", action = { beginTagPlacement(kind) })
            },
            widthDp = 220,
        )
    }

    private fun beginTagPlacement(kind: CanvasTagKind) {
        textInputLayer.abandonTemporaryEditors()
        textInputLayer.endEditing()
        singleFingerPanEnabled = false
        transformGestureActive = false
        tagLayer.beginPlacement(kind)
        selectionOverlay.selectionEnabled = false
        Toast.makeText(this, "点击页面放置${kind.label}标记", Toast.LENGTH_SHORT).show()
    }

    private fun showFindTags(anchor: View) {
        val tags = tagLayer.allTags()
        if (tags.isEmpty()) {
            Toast.makeText(this, "当前页面还没有标记", Toast.LENGTH_SHORT).show()
            return
        }
        MojianCommandMenu.show(
            this,
            anchor,
            tags.mapIndexed { index, tag ->
                MojianCommandMenu.Item("${index + 1}. ${tag.kind.label}", icon = "tag_search", action = {
                    viewport.centerDocumentPoint(tag.documentX, tag.documentY, tagLayer.width * 0.5f, tagLayer.height * 0.5f)
                    dryInkView.setViewport(viewport.documentToViewMatrix())
                    insertContentLayer.invalidate()
                    tagLayer.invalidate()
                    selectionOverlay.invalidate()
                    tagLayer.focusTag(index)
                })
            },
            widthDp = 240,
        )
    }

    private fun handleInsertSpace(anchor: View) {
        textInputLayer.abandonTemporaryEditors()
        textInputLayer.endEditing()
        tagLayer.cancelPlacement()
        singleFingerPanEnabled = false
        transformGestureActive = false
        toolDetailPopup?.dismiss()
        toolDetailPopup = null
        insertSpaceActive = false
        insertSpacePointerId = null
        dryInkView.setInsertSpaceHoverY(null)
        dryInkView.setInsertSpaceGuide(null, null)
        insertSpaceModeEnabled = !insertSpaceModeEnabled
        updateSelectionInteraction()
        renderToolShelf()
        Toast.makeText(
            this,
            if (insertSpaceModeEnabled) "已开启插入空白空间：笔尖悬浮可预览基准线，按下并向下拖动可推移下方内容" else "已退出插入空白空间",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun handleInsertSpaceGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val toolType = event.getToolType(pointerIndex)
                val device = if (toolType == MotionEvent.TOOL_TYPE_FINGER) DirectInputDevice.FINGER else if (isStylus(toolType)) DirectInputDevice.PEN else DirectInputDevice.MOUSE
                if (!DirectInputPolicy.canDragInsertSpace(device)) return false
                insertSpacePointerId = event.getPointerId(pointerIndex)
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
                    val pointerIndex = insertSpacePointerId?.let(event::findPointerIndex) ?: -1
                    if (pointerIndex < 0) return false
                    val docPoint = viewport.viewToDocument(event.getX(pointerIndex), event.getY(pointerIndex))
                    insertSpaceCurrentDocY = docPoint.y
                    dryInkView.setInsertSpaceGuide(insertSpaceStartDocY, insertSpaceCurrentDocY)
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                if (insertSpaceActive && event.getPointerId(event.actionIndex) == insertSpacePointerId) {
                    val deltaY = insertSpaceCurrentDocY - insertSpaceStartDocY
                    if (kotlin.math.abs(deltaY) >= 2f) {
                        dryInkView.insertVerticalSpace(insertSpaceStartDocY, deltaY)
                        tagLayer.insertVerticalSpace(insertSpaceStartDocY, deltaY)
                        insertContentLayer.insertVerticalSpace(insertSpaceStartDocY, deltaY)
                        expandPageIfNeeded()
                    }
                    insertSpaceActive = false
                    insertSpacePointerId = null
                    dryInkView.setInsertSpaceGuide(null, null)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (insertSpaceActive) {
                    insertSpaceActive = false
                    insertSpacePointerId = null
                    dryInkView.setInsertSpaceGuide(null, null)
                    return true
                }
            }
        }
        return insertSpaceActive
    }

    private fun cancelInsertSpaceGesture() {
        insertSpaceActive = false
        insertSpacePointerId = null
        dryInkView.setInsertSpaceGuide(null, null)
    }

    private fun showBackgroundFormatMenu(anchor: View) {
        val current = workspace.selectedPage()?.viewSettings?.backgroundFormat ?: return
        showBackgroundFormatChooser(anchor, current) { format ->
            updateCurrentPageView { it.backgroundFormat = format }
        }
    }

    private fun showBackgroundFormatChooser(
        anchor: View,
        current: CanvasBackgroundFormat,
        onSelected: (CanvasBackgroundFormat) -> Unit,
    ) {
        MojianCommandMenu.show(
            this,
            anchor,
            PageAppearanceCatalog.backgroundFormats.map { format ->
                MojianCommandMenu.Item(
                    label = format.label,
                    icon = "rule_lines",
                    checked = current == format,
                    action = { onSelected(format) },
                )
            },
            widthDp = 242,
        )
    }

    private fun showNewPageTemplateDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val draft = lastPageTemplateSettings.copy(a4PageCount = 1)
        var activeTab = PageCreationTab.PAGE
        val tabHost = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val optionsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val actionHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        lateinit var renderDialog: () -> Unit

        renderDialog = {
            tabHost.removeAllViews()
            PageCreationTab.entries.forEach { tab ->
                val selected = tab == activeTab
                tabHost.addView(
                    TextView(this).apply {
                        text = if (tab == PageCreationTab.PAGE) "页" else "PDF"
                        gravity = Gravity.CENTER
                        setTextColor(if (selected) ACCENT_PURPLE else TEXT_SECONDARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        background = if (selected) bottomBorderDrawable(Color.TRANSPARENT, ACCENT_PURPLE) else rounded(Color.TRANSPARENT, 0f, Color.TRANSPARENT)
                        setOnClickListener {
                            activeTab = tab
                            renderDialog()
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(38), 1f),
                )
            }
            optionsHost.removeAllViews()
            if (activeTab == PageCreationTab.PAGE) {
                optionsHost.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        PageAppearanceCatalog.paperTemplates.forEachIndexed { index, paperSize ->
                            addView(
                                pageTemplateCard(paperSize, draft.paperSize == paperSize) {
                                    draft.paperSize = paperSize
                                    draft.customDocumentWidth = null
                                    draft.customDocumentHeight = null
                                    draft.a4PageCount = 1
                                    renderDialog()
                                },
                                LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                                    marginStart = if (index == 0) 0 else dp(5)
                                    marginEnd = if (index == PageAppearanceCatalog.paperTemplates.lastIndex) 0 else dp(5)
                                },
                            )
                        }
                    },
                    LinearLayout.LayoutParams(MATCH, dp(82)),
                )
                optionsHost.addView(
                    templateSettingButton("rule_lines", draft.backgroundFormat.label) { anchor ->
                        showBackgroundFormatChooser(anchor, draft.backgroundFormat) { format ->
                            draft.backgroundFormat = format
                            renderDialog()
                        }
                    },
                    LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(10) },
                )
                optionsHost.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            ImageView(this@MainActivity).apply {
                                setImageDrawable(fluentIcon("paint_brush"))
                                imageTintList = ColorStateList.valueOf(TEXT_SECONDARY)
                            },
                            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) },
                        )
                        PageAppearanceCatalog.pageColors.forEach { color ->
                            addView(
                                templateColorButton(color, draft.darkBackground, color == draft.pageColor) {
                                    draft.pageColor = color
                                    renderDialog()
                                },
                                LinearLayout.LayoutParams(dp(42), dp(38)).apply { marginEnd = dp(4) },
                            )
                        }
                    },
                    LinearLayout.LayoutParams(MATCH, dp(46)).apply { topMargin = dp(4) },
                )
            } else {
                optionsHost.addView(
                    pageTemplateCard(PaperSize.PDF, selected = true, showLabel = false) {},
                    LinearLayout.LayoutParams(MATCH, dp(82)),
                )
            }
            actionHost.removeAllViews()
            actionHost.addView(
                dialogActionButton("取消", primary = false) { dialog.dismiss() },
                LinearLayout.LayoutParams(dp(72), dp(36)).apply { marginEnd = dp(8) },
            )
            actionHost.addView(
                dialogActionButton(if (activeTab == PageCreationTab.PAGE) "创建" else "选择", primary = true) {
                    if (activeTab == PageCreationTab.PDF) {
                        pendingPdfPageTarget = PdfPageTarget(workspace.id, workspace.selectedSectionId, splitViewState.focusedSide.takeIf { splitViewState.enabled })
                        dialog.dismiss()
                        launchDocumentPicker(REQUEST_PDF_PAGES, "application/pdf", requireSelectedPage = false)
                    } else {
                        val settings = draft.copy(
                            a4PageCount = 1,
                            boundlessDocumentWidth = BoundlessPageLayout.initialWidth,
                            boundlessDocumentHeight = BoundlessPageLayout.initialHeight,
                        )
                        val page = workspace.addPage(viewSettings = settings)
                        ensureA4Capacity(page)
                        lastPageTemplateSettings = settings.copy(a4PageCount = 1)
                        persistLastPageTemplate()
                        persistPageViewSettings()
                        dialog.dismiss()
                        renderPages()
                        persistWorkspacePreferences()
                    }
                },
                LinearLayout.LayoutParams(dp(72), dp(36)),
            )
        }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = rounded(TOOLBAR_BACKGROUND, dp(4).toFloat(), BORDER_STRONG)
                addView(
                    TextView(this@MainActivity).apply {
                        text = "新建页面"
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(MATCH, dp(34)),
                )
                addView(tabHost, LinearLayout.LayoutParams(MATCH, dp(38)))
                addView(optionsHost, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
                addView(actionHost, LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(8) })
            }
        renderDialog()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels - dp(32)).coerceAtMost(dp(430)), WRAP)
        }
    }

    private fun pageTemplateCard(
        paperSize: PaperSize,
        selected: Boolean,
        showLabel: Boolean = true,
        action: () -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = toggleBackground(selected, opaqueWhenIdle = true)
            addView(
                ImageView(this@MainActivity).apply {
                    setImageDrawable(
                        when (paperSize) {
                            PaperSize.A4 -> fluentIcon("document_page")
                            PaperSize.PDF -> fluentIcon("document_pdf", "document_page")
                            PaperSize.BOUNDLESS -> fluentIcon("full_screen_maximize")
                        },
                    )
                    imageTintList = ColorStateList.valueOf(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
                },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { bottomMargin = if (showLabel) dp(5) else 0 },
            )
            if (showLabel) {
                addView(
                    TextView(this@MainActivity).apply {
                        text = paperSize.menuLabel
                        gravity = Gravity.CENTER
                        setTextColor(if (selected) ACCENT_PURPLE else TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    },
                    LinearLayout.LayoutParams(MATCH, dp(20)),
                )
            }
            setOnClickListener { action() }
            contentDescription = paperSize.menuLabel
            setDarkTooltip(this, paperSize.menuLabel)
        }

    private fun templateSettingButton(
        icon: String,
        value: String,
        action: (View) -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            background = rounded(Color.rgb(42, 42, 42), dp(2).toFloat(), BORDER_STRONG)
            addView(
                ImageView(this@MainActivity).apply {
                    setImageDrawable(fluentIcon(icon))
                    imageTintList = ColorStateList.valueOf(TEXT_SECONDARY)
                },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = value
                    setTextColor(TEXT_PRIMARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    gravity = Gravity.CENTER_VERTICAL
                },
                LinearLayout.LayoutParams(0, MATCH, 1f),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "⌄"
                    setTextColor(TEXT_SECONDARY)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(22), MATCH),
            )
            setOnClickListener { action(this) }
            contentDescription = value
            setDarkTooltip(this, value)
        }

    private fun templateColorButton(
        color: PageColor,
        darkBackground: Boolean,
        selected: Boolean,
        action: () -> Unit,
    ): View =
        FrameLayout(this).apply {
            background = toggleBackground(selected)
            addView(
                View(this@MainActivity).apply {
                    background =
                        rounded(
                            color.displayArgb(darkBackground),
                            dp(2).toFloat(),
                            if (selected) ACCENT_PURPLE else BORDER_STRONG,
                        )
                },
                FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER),
            )
            setOnClickListener { action() }
            contentDescription = color.description
            setDarkTooltip(this, color.description)
        }

    private fun dialogActionButton(
        label: String,
        primary: Boolean,
        action: () -> Unit,
    ): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.rgb(30, 20, 34) else TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background =
                rounded(
                    if (primary) ACCENT_PURPLE else Color.rgb(42, 42, 42),
                    dp(2).toFloat(),
                    if (primary) ACCENT_PURPLE else BORDER_STRONG,
                )
            setOnClickListener { action() }
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

            sectionList =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setOnDragListener { _, event -> handleSectionListDrag(event) }
                }
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
                                showNewPageTemplateDialog()
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
            pageList =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setOnDragListener { _, event -> handlePageListDrag(event) }
                }
            addView(
                ScrollView(this@MainActivity).apply {
                    isFillViewport = true
                    addView(pageList, ViewGroup.LayoutParams(MATCH, WRAP))
                },
                LinearLayout.LayoutParams(MATCH, 0, 1f),
            )
        }.also { it.background = bordered(PAGE_LIST_BACKGROUND, BORDER_COLOR, 0f) }

    private fun buildSplitCanvasHost(): View {
        splitCanvasHost =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(PAPER_COLOR)
            }
        leftPageHost = splitPaneHost(SplitPaneSide.LEFT)
        rightPageHost = splitPaneHost(SplitPaneSide.RIGHT).apply { visibility = View.GONE }
        splitDivider = FrameLayout(this).apply {
            setBackgroundColor(PAPER_COLOR)
            visibility = View.GONE
            addView(
                View(this@MainActivity).apply { setBackgroundColor(BORDER_COLOR) },
                FrameLayout.LayoutParams(1, MATCH, Gravity.CENTER_HORIZONTAL),
            )
        }
        splitCanvasHost.addView(leftPageHost, LinearLayout.LayoutParams(0, MATCH, 1f))
        splitCanvasHost.addView(splitDivider, LinearLayout.LayoutParams(dp(5), MATCH))
        splitCanvasHost.addView(rightPageHost, LinearLayout.LayoutParams(0, MATCH, 1f))
        leftPageHost.addView(buildPaper(), FrameLayout.LayoutParams(MATCH, MATCH))
        updateSplitFocusVisuals()
        return splitCanvasHost
    }

    private fun splitPaneHost(side: SplitPaneSide): FrameLayout =
        FrameLayout(this).apply {
            setBackgroundColor(PAPER_COLOR)
            clipChildren = false
            clipToPadding = false
            setOnClickListener { focusSplitPane(side) }
            contentDescription = if (side == SplitPaneSide.LEFT) "左侧页面" else "右侧页面"
        }

    private fun buildPaper(): View {
        paperContainer = CanvasInputHost(this).apply { setBackgroundColor(PAPER_COLOR) }
        pageSurfaceBackground = PageSurfaceView(this, viewport, PageSurfaceLayer.BACKGROUND)
        pageSurfaceForeground = PageSurfaceView(this, viewport, PageSurfaceLayer.FOREGROUND)
        dryInkView = DryInkView(this)
        dryInkView.backgroundFormat = workspace.selectedPage()?.viewSettings?.backgroundFormat ?: legacyBackgroundFormat
        canvasBackgroundLayer = CanvasBackgroundLayer(this, dryInkView)
        wetInkView = InProgressStrokesView(this)
        insertContentLayer = InsertContentLayer(this, viewport, pageContentRepository)
        tagLayer = CanvasTagLayer(this, viewport)
        textInputLayer = CanvasTextInputLayer(this, viewport)
        selectionOverlay =
            SelectionOverlayView(this, viewport).apply {
                selectionBoundsProvider = ::combinedSelectionBounds
                onTranslateSelection = { deltaX, deltaY ->
                    dryInkView.translateSelection(deltaX, deltaY)
                    tagLayer.translateSelection(deltaX, deltaY)
                    textInputLayer.translateSelection(deltaX, deltaY)
                    insertContentLayer.translateSelection(deltaX, deltaY)
                }
                onScaleSelection = { anchorX, anchorY, factor ->
                    dryInkView.scaleSelection(anchorX, anchorY, factor)
                    tagLayer.scaleSelection(anchorX, anchorY, factor)
                    textInputLayer.scaleSelection(anchorX, anchorY, factor)
                    insertContentLayer.scaleSelection(anchorX, anchorY, factor)
                }
                onTransformFinished = {
                    insertContentLayer.finishSelectionTransform()
                    saveBoundPageSession()
                    expandPageIfNeeded()
                    renderToolDetails()
                }
                selectedContentHitTest = ::selectedContentHitAt
                editingTextHitTest = textInputLayer::isEditingAt
                onDirectTap = ::handleDirectTap
                onDirectLongPress = ::handleDirectLongPress
                onBeginMarquee = { viewX, viewY -> beginContentLasso(viewX, viewY, LassoShape.RECTANGLE) }
                onExtendMarquee = { viewX, viewY ->
                    val point = viewport.viewToDocument(viewX, viewY)
                    dryInkView.extendLasso(point)
                }
                onFinishMarquee = ::finishContentLasso
                onCancelMarquee = ::cancelDirectMarquee
            }
        tagLayer.onPlacementFinished = { tag ->
            updateSelectionInteraction()
            expandPageIfNeeded()
            Toast.makeText(this, "已放置${tag.kind.label}标记", Toast.LENGTH_SHORT).show()
            renderFullPageToolbar()
        }
        tagLayer.onTagClicked = {
            dryInkView.clearSelection()
            textInputLayer.clearSelection()
            insertContentLayer.clearSelection()
            updateSelectionInteraction()
            renderToolDetails()
        }
        insertContentLayer.onDocumentChanged = { document ->
            pageContentRepository.save(document)
            expandPageIfNeeded()
        }
        insertContentLayer.onObjectContextMenuRequested = ::showInsertObjectContextMenu
        insertContentLayer.onSelectionChanged = { selected ->
            if (selected != null) {
                dryInkView.clearSelection()
                tagLayer.clearSelection()
                textInputLayer.clearSelection()
            }
            updateSelectionInteraction()
            renderToolDetails()
        }
        textInputLayer.onSelectionChanged = { count ->
            if (count > 0) {
                dryInkView.clearSelection()
                tagLayer.clearSelection()
                insertContentLayer.clearSelection()
            }
            updateSelectionInteraction()
            renderToolDetails()
        }
        textInputLayer.onEditingChanged = { updateSelectionInteraction() }
        textInputLayer.onContentChanged = ::expandPageIfNeeded
        dryInkView.onContentChanged = { expandPageIfNeeded(); persistGeneratedShapes() }
        textInputLayer.onPageGesture = ::handleFingerGesture
        paperContainer.onPointerEventObserved = ::observeCanvasPointerEvent
        dryInkView.setViewport(viewport.documentToViewMatrix())
        paperContainer.addView(pageSurfaceBackground, FrameLayout.LayoutParams(MATCH, MATCH))
        paperContainer.addView(canvasBackgroundLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        paperContainer.addView(insertContentLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        paperContainer.addView(dryInkView, FrameLayout.LayoutParams(MATCH, MATCH))
        paperContainer.addView(wetInkView, FrameLayout.LayoutParams(MATCH, MATCH))
        paperContainer.addView(textInputLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        paperContainer.addView(tagLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        paperContainer.addView(selectionOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        paperContainer.addView(pageSurfaceForeground, FrameLayout.LayoutParams(MATCH, MATCH))

        floatingToolbox = buildFloatingToolbox()
        paperContainer.addView(
            floatingToolbox,
            FrameLayout.LayoutParams(WRAP, dp(48), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(7)
            },
        )
        fullPagePaneButtons = buildFullPagePaneButtons()
        paperContainer.addView(
            fullPagePaneButtons,
            FrameLayout.LayoutParams(dp(36), WRAP, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(7)
                leftMargin = dp(7)
            },
        )
        paperContainer.addView(
            buildCanvasActions(),
            FrameLayout.LayoutParams(WRAP, dp(36), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(6)
                rightMargin = dp(7)
            },
        )
        return paperContainer
    }

    private fun toggleSplitView() {
        if (activeStrokes.isNotEmpty() || workspace.selectedPage() == null) return
        if (splitViewState.enabled) exitSplitView() else enterSplitView()
    }

    private fun enterSplitView() {
        val current = notebookLibrary.captureLocation()
        if (!splitViewState.enter(current)) return
        saveBoundPageSession()
        rightPageHost.visibility = View.VISIBLE
        splitDivider.visibility = View.VISIBLE
        showSplitFocusPulse()
        splitCanvasHost.post {
            if (!splitViewState.enabled) return@post
            captureLivePagePreview()?.let { installPreview(rightPageHost, it) }
        }
        refreshViewRibbonState()
    }

    private fun exitSplitView() {
        if (!splitViewState.enabled) return
        if (splitViewState.focusedSide != SplitPaneSide.RIGHT) focusSplitPane(SplitPaneSide.RIGHT)
        saveBoundPageSession()
        val kept = splitViewState.exitKeepingRight() ?: return
        mainHandler.removeCallbacks(hideSplitFocus)
        splitFocusVisible = false
        notebookLibrary.restoreLocation(kept)
        moveLivePaperTo(leftPageHost)
        clearPreview(rightPageHost)
        rightPageHost.visibility = View.GONE
        splitDivider.visibility = View.GONE
        renderWorkspace()
        updateSplitFocusVisuals()
        refreshViewRibbonState()
    }

    private fun focusSplitPane(side: SplitPaneSide) {
        if (!splitViewState.enabled || side == splitViewState.focusedSide) return
        val destination = splitViewState.location(side) ?: return
        captureLivePagePreview()?.let { preview ->
            installPreview(hostFor(splitViewState.focusedSide), preview)
        }
        saveBoundPageSession()
        if (!splitViewState.focus(side) || !notebookLibrary.restoreLocation(destination)) return
        moveLivePaperTo(hostFor(side))
        renderWorkspace()
        showSplitFocusPulse()
    }

    private fun hostFor(side: SplitPaneSide): FrameLayout =
        if (side == SplitPaneSide.LEFT) leftPageHost else rightPageHost

    private fun moveLivePaperTo(host: FrameLayout) {
        (paperContainer.parent as? ViewGroup)?.removeView(paperContainer)
        clearPreview(host)
        host.addView(paperContainer, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun captureLivePagePreview(): Bitmap? {
        val width = paperContainer.width
        val height = paperContainer.height
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(PAPER_COLOR)
            pageSurfaceBackground.draw(canvas)
            insertContentLayer.draw(canvas)
            dryInkView.draw(canvas)
            wetInkView.draw(canvas)
            textInputLayer.draw(canvas)
            tagLayer.draw(canvas)
            pageSurfaceForeground.draw(canvas)
        }
    }

    private fun installPreview(host: FrameLayout, bitmap: Bitmap) {
        clearPreview(host)
        host.addView(
            ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                tag = bitmap
                contentDescription = host.contentDescription
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
    }

    private fun clearPreview(host: FrameLayout) {
        for (index in 0 until host.childCount) {
            val child = host.getChildAt(index)
            if (child !== paperContainer) (child.tag as? Bitmap)?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
        host.removeViews(0, host.childCount)
    }

    private fun updateSplitFocusVisuals() {
        if (!::leftPageHost.isInitialized) return
        for (side in SplitPaneSide.entries) {
            val focused = !splitViewState.enabled || splitViewState.focusedSide == side
            hostFor(side).foreground =
                rounded(
                    Color.TRANSPARENT,
                    0f,
                    if (focused && splitViewState.enabled && splitFocusVisible) ACCENT_PURPLE else Color.TRANSPARENT,
                )
        }
    }

    private fun showSplitFocusPulse() {
        mainHandler.removeCallbacks(hideSplitFocus)
        splitFocusVisible = true
        updateSplitFocusVisuals()
        mainHandler.postDelayed(hideSplitFocus, SPLIT_FOCUS_PULSE_MILLIS)
    }

    private fun saveBoundPageSession() {
        val key = boundPageKey ?: return
        persistGeneratedShapes()
        pageSessions[key] =
            PageSessionState(
                strokes = dryInkView.snapshotStrokes(),
                tags = tagLayer.snapshotTags(),
                textBoxes = textInputLayer.snapshotEditors(),
                viewport = viewport.snapshot(),
            )
    }

    private fun buildFloatingToolbox(): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            elevation = dp(7).toFloat()
            background = rounded(TOOLBAR_BACKGROUND, dp(3).toFloat(), BORDER_STRONG)
            floatingToolShelfRow =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(2), dp(4), dp(2))
                }
            addView(floatingToolShelfRow, ViewGroup.LayoutParams(WRAP, MATCH))
        }

    private fun buildFullPagePaneButtons(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            fullPageSectionPaneToggle =
                canvasActionButton("navigation", "折叠或展开分区栏", sectionPaneVisible) { toggleSectionPane() }
            fullPagePagePaneToggle =
                canvasActionButton("panel_left", "折叠或展开页面栏", pagePaneVisible) { togglePagePane() }
            addView(fullPageSectionPaneToggle, LinearLayout.LayoutParams(dp(34), dp(34)).apply { bottomMargin = dp(4) })
            addView(fullPagePagePaneToggle, LinearLayout.LayoutParams(dp(34), dp(34)))
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
                setFullPageMode(!fullPageMode)
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
            installNavigationLongPressAndDrag(notebookRow, payload = null) { showNotebookMenu(notebookRow, notebook) }
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
                item.tag = SectionRowTag(notebook.id, section.id)
                installNavigationLongPressAndDrag(
                    item,
                    NavigationDragPayload.Section(notebook.id, section.id),
                ) { showSectionMenu(item, notebook, section) }
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
            applySelectedPageAppearance()
            return
        }
        for (row in rows) {
            when (row) {
                is PagePaneRow.SectionHeader -> addRecursiveSectionHeader(row)
                is PagePaneRow.PageItem -> addPageRow(row)
            }
        }
        applySelectedPageAppearance()
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
        installNavigationLongPressAndDrag(item, payload = null) { showSectionMenu(item, workspace, row.section) }
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
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            TextView(this@MainActivity).apply {
                                text = page.title
                                setTextColor(TEXT_PRIMARY)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                typeface = if (selected) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                                maxLines = 1
                            },
                            LinearLayout.LayoutParams(0, MATCH, 1f),
                        )
                        if (page.pdfPage != null) {
                            addView(
                                TextView(this@MainActivity).apply {
                                    text = "PDF"
                                    gravity = Gravity.CENTER
                                    setTextColor(Color.rgb(130, 130, 130))
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                                },
                                LinearLayout.LayoutParams(dp(30), MATCH),
                            )
                        }
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
        item.tag = PageRowTag(workspace.id, row.sectionId, page.id)
        installNavigationLongPressAndDrag(
            item,
            NavigationDragPayload.Page(workspace.id, row.sectionId, page.id),
        ) { showPageMenu(item, workspace, row.sectionId, page) }
        pageList.addView(item, LinearLayout.LayoutParams(MATCH, dp(58)).apply { setMargins(dp(6), dp(1), dp(6), dp(1)) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installNavigationLongPressAndDrag(
        view: View,
        payload: NavigationDragPayload?,
        onLongPress: () -> Unit,
    ) {
        view.setOnContextClickListener {
            onLongPress()
            true
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        val gesture = NavigationLongPressGesture()
        val armDrag =
            Runnable {
                gesture.armDrag()
            }
        val showMenu =
            Runnable {
                if (!gesture.showMenu()) return@Runnable
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                view.parent?.requestDisallowInterceptTouchEvent(true)
                view.isPressed = false
                onLongPress()
            }
        view.setOnTouchListener { _, event ->
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    gesture.reset()
                    mainHandler.postDelayed(armDrag, NAVIGATION_DRAG_ARM_MILLIS)
                    mainHandler.postDelayed(showMenu, ViewConfiguration.getLongPressTimeout().toLong())
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val distance = kotlin.math.hypot(event.rawX - downX, event.rawY - downY)
                    if (!gesture.isDragArmed && !gesture.isMenuShown && distance > slop) {
                        mainHandler.removeCallbacks(armDrag)
                        mainHandler.removeCallbacks(showMenu)
                    }
                    if (gesture.isDragArmed && !gesture.isDragging && payload != null && distance > slop) {
                        mainHandler.removeCallbacks(showMenu)
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        gesture.recordDragStart(
                            view.startDragAndDrop(
                                ClipData.newPlainText("navigation", ""),
                                View.DragShadowBuilder(view),
                                payload,
                                0,
                            ),
                        )
                    }
                    gesture.isDragArmed || gesture.isDragging || gesture.isMenuShown
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(armDrag)
                    mainHandler.removeCallbacks(showMenu)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    val action = gesture.release()
                    if (action != NavigationReleaseAction.CLICK) view.isPressed = false
                    when (action) {
                        NavigationReleaseAction.DROP_ONLY -> Unit
                        NavigationReleaseAction.CONSUMED -> Unit
                        NavigationReleaseAction.CLICK -> Unit
                    }
                    action != NavigationReleaseAction.CLICK
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(armDrag)
                    mainHandler.removeCallbacks(showMenu)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    gesture.reset()
                    false
                }
                else -> gesture.isDragArmed || gesture.isDragging || gesture.isMenuShown
            }
        }
    }

    private fun handleSectionListDrag(event: DragEvent): Boolean {
        val payload = event.localState as? NavigationDragPayload.Section ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_LOCATION -> {
                autoScrollNavigationList(sectionList, event.y)
                navigationDropTarget = findNavigationDropTarget(sectionList, event.y) { it is SectionRowTag && it.notebookId == payload.notebookId }
                true
            }
            DragEvent.ACTION_DROP -> {
                val target = navigationDropTarget?.tag as? SectionRowTag
                val notebook = notebookLibrary.notebooks.firstOrNull { it.id == payload.notebookId }
                val moved =
                    target != null && target.notebookId == payload.notebookId &&
                        notebook?.moveSectionAdjacent(payload.sectionId, target.sectionId, navigationDropTarget?.after == true) == true
                navigationDropTarget = null
                if (moved) {
                    persistWorkspacePreferences()
                    renderWorkspace()
                }
                moved
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                navigationDropTarget = null
                true
            }
            else -> true
        }
    }

    private fun handlePageListDrag(event: DragEvent): Boolean {
        val payload = event.localState as? NavigationDragPayload.Page ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> payload.notebookId == workspace.id
            DragEvent.ACTION_DRAG_LOCATION -> {
                autoScrollNavigationList(pageList, event.y)
                navigationDropTarget = findNavigationDropTarget(pageList, event.y) { it is PageRowTag && it.notebookId == payload.notebookId }
                true
            }
            DragEvent.ACTION_DROP -> {
                val target = navigationDropTarget?.tag as? PageRowTag
                val notebook = notebookLibrary.notebooks.firstOrNull { it.id == payload.notebookId }
                val moved =
                    target != null && target.notebookId == payload.notebookId &&
                        notebook?.movePageAdjacent(
                            payload.sectionId,
                            payload.pageId,
                            target.sectionId,
                            target.pageId,
                            navigationDropTarget?.after == true,
                        ) == true
                navigationDropTarget = null
                if (moved) {
                    persistWorkspacePreferences()
                    renderWorkspace()
                }
                moved
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                navigationDropTarget = null
                true
            }
            else -> true
        }
    }

    private fun findNavigationDropTarget(
        container: LinearLayout,
        y: Float,
        accepts: (Any) -> Boolean,
    ): NavigationDropTarget? {
        val candidates =
            (0 until container.childCount)
                .map(container::getChildAt)
                .filter { child -> child.tag?.let(accepts) == true }
        val closest = candidates.minByOrNull { child -> kotlin.math.abs(y - (child.top + child.bottom) / 2f) } ?: return null
        return NavigationDropTarget(closest.tag, y >= (closest.top + closest.bottom) / 2f)
    }

    private fun autoScrollNavigationList(container: LinearLayout, y: Float) {
        val scroll = container.parent as? ScrollView ?: return
        val edge = dp(44)
        when {
            y - scroll.scrollY < edge -> scroll.scrollBy(0, -dp(12))
            scroll.scrollY + scroll.height - y < edge -> scroll.scrollBy(0, dp(12))
        }
    }

    private fun showNotebookMenu(anchor: View, notebook: NotebookWorkspace) {
        MojianCommandMenu.show(
            this,
            anchor,
            listOf(
                MojianCommandMenu.Item("重命名", "pen", action = {
                    showRenameDialog("重命名笔记本", notebook.title) { title ->
                        if (notebookLibrary.renameNotebook(notebook.id, title)) finishNavigationMutation()
                    }
                }),
                MojianCommandMenu.Item("删除", "delete_dismiss", destructive = true, action = { confirmDeleteNotebook(notebook) }),
                MojianCommandMenu.Item("新建分区", "add", dividerBefore = true, action = {
                    notebookLibrary.selectNotebook(notebook.id)
                    notebook.focusNotebook()
                    notebook.addSectionAtFocus()
                    finishNavigationMutation()
                }),
            ),
            widthDp = 244,
        )
    }

    private fun confirmDeleteNotebook(notebook: NotebookWorkspace) {
        val sections = notebook.allSections()
        val pages = sections.sumOf { it.pages.size }
        AlertDialog.Builder(this)
            .setTitle("删除笔记本？")
            .setMessage("将删除“${notebook.title}”以及其中 ${sections.size} 个分区、$pages 个页面和本地附件。此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                saveBoundPageSession()
                pageContentRepository.deleteNotebookData(notebook.id)
                if (!notebookLibrary.deleteNotebook(notebook.id)) return@setPositiveButton
                pageSessions.keys.removeAll { it.notebookId == notebook.id }
                if (boundPageKey?.notebookId == notebook.id) {
                    boundPageKey = null
                    dryInkView.replaceStrokes(emptyList())
                    tagLayer.replaceTags(emptyList())
                    textInputLayer.replaceEditors(emptyList())
                    insertContentLayer.bind(null)
                }
                if (pageNavigationClipboard?.notebookId == notebook.id) pageNavigationClipboard = null
                if (insertObjectClipboard?.source?.notebookId == notebook.id) insertObjectClipboard = null
                for (side in SplitPaneSide.entries) {
                    if (splitViewState.location(side)?.notebookId == notebook.id) clearPreview(hostFor(side))
                }
                splitViewState.replaceNotebookLocations(notebook.id, notebookLibrary.captureLocation())
                finishNavigationMutation()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSectionMenu(anchor: View, notebook: NotebookWorkspace, section: SectionNode) {
        val labels = NavigationMenuCatalog.sectionItems
        MojianCommandMenu.show(
            this,
            anchor,
            listOf(
                MojianCommandMenu.Item(labels[0], "pen", action = {
                showRenameDialog("重命名分区", section.title) { title ->
                    if (notebook.renameSection(section.id, title)) finishNavigationMutation()
                }
                }),
                MojianCommandMenu.Item(labels[1], "document_pdf", action = { startSectionExport(notebook, section) }),
                MojianCommandMenu.Item(labels[2], "delete_dismiss", destructive = true, action = { confirmDeleteSection(notebook, section) }),
                MojianCommandMenu.Item(labels[3], "copy", dividerBefore = true, action = { showSectionMoveOrCopyDialog(notebook, section) }),
                MojianCommandMenu.Item(labels[4], "split_view", action = { showSectionMergeDialog(notebook, section) }),
                MojianCommandMenu.Item(labels[5], "link", dividerBefore = true, action = { copyNavigationLink("分区链接", sectionLink(notebook.id, section.id)) }),
                MojianCommandMenu.Item(labels[6], "add", dividerBefore = true, action = {
                    notebookLibrary.selectNotebook(notebook.id)
                    notebook.addSection(section.id)
                    finishNavigationMutation()
                }),
            ),
            widthDp = 244,
        )
    }

    private fun showPageMenu(anchor: View, notebook: NotebookWorkspace, sectionId: String, page: NotePage) {
        val labels = NavigationMenuCatalog.pageItems
        MojianCommandMenu.show(
            this,
            anchor,
            listOf(
                MojianCommandMenu.Item(labels[0], "pen", action = {
                showRenameDialog("重命名页面", page.title) { title ->
                    if (notebook.renamePage(sectionId, page.id, title)) finishNavigationMutation()
                }
                }),
                MojianCommandMenu.Item(labels[1], "delete_dismiss", destructive = true, action = { confirmDeletePage(notebook, sectionId, page) }),
                MojianCommandMenu.Item(labels[2], "cut", dividerBefore = true, action = {
                    pageNavigationClipboard = PageNavigationClipboard.Cut(notebook.id, sectionId, page.id)
                    Toast.makeText(this@MainActivity, "已剪切“${page.title}”", Toast.LENGTH_SHORT).show()
                }),
                MojianCommandMenu.Item(labels[3], "copy", action = {
                    pageNavigationClipboard = PageNavigationClipboard.Copy(notebook.id, sectionId, page.id)
                    Toast.makeText(this@MainActivity, "已复制“${page.title}”", Toast.LENGTH_SHORT).show()
                }),
                MojianCommandMenu.Item(labels[4], "clipboard_paste", enabled = pageNavigationClipboard != null, action = {
                    pastePageAfter(notebook, sectionId, page.id)
                }),
                MojianCommandMenu.Item(labels[5], "link", dividerBefore = true, action = {
                    copyNavigationLink("页面链接", pageLink(notebook.id, sectionId, page.id))
                }),
                MojianCommandMenu.Item(labels[6], "copy", dividerBefore = true, action = {
                    showPageMoveOrCopyDialog(notebook, sectionId, page)
                }),
            ),
            widthDp = 244,
        )
    }

    private fun showRenameDialog(title: String, currentValue: String, onSave: (String) -> Unit) {
        val input =
            EditText(this).apply {
                setText(currentValue)
                selectAll()
                setSingleLine(true)
                setPadding(dp(18), dp(8), dp(18), dp(8))
            }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("保存") { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
        input.requestFocus()
    }

    private fun confirmDeleteSection(notebook: NotebookWorkspace, section: SectionNode) {
        val pages = notebook.pagesInSectionTree(section.id)
        val childCount = notebook.allSections().count { candidate -> notebook.breadcrumb(candidate.id).dropLast(1).any { it.id == section.id } }
        val message =
            buildString {
                append("将删除“${section.title}”")
                if (childCount > 0 || pages.isNotEmpty()) append("以及其中 $childCount 个子分区、${pages.size} 个页面")
                append("。此操作无法撤销。")
            }
        AlertDialog.Builder(this)
            .setTitle("删除分区？")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                saveBoundPageSession()
                val removedPageIds = pages.mapTo(mutableSetOf()) { it.id }
                pages.forEach { rehomeSharedPdfSource(notebook, it, removedPageIds) }
                val removed = notebook.deleteSection(section.id) ?: return@setPositiveButton
                removedPageIds(removed).forEach { pageId -> removePageRuntimeAndData(notebook.id, pageId) }
                if (notebookLibrary.activeNotebookId == notebook.id && workspace.focusedSectionId == null && workspace.sections.isNotEmpty()) {
                    workspace.selectSection(workspace.sections.first().id)
                }
                finishNavigationMutation()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeletePage(notebook: NotebookWorkspace, sectionId: String, page: NotePage) {
        AlertDialog.Builder(this)
            .setTitle("删除页面？")
            .setMessage("将删除“${page.title}”及其本地内容。此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                saveBoundPageSession()
                rehomeSharedPdfSource(notebook, page)
                if (notebook.deletePage(sectionId, page.id) != null) {
                    removePageRuntimeAndData(notebook.id, page.id)
                    if (pageNavigationClipboard?.pageId == page.id && pageNavigationClipboard?.notebookId == notebook.id) {
                        pageNavigationClipboard = null
                    }
                    finishNavigationMutation()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSectionMoveOrCopyDialog(notebook: NotebookWorkspace, section: SectionNode) {
        val candidates =
            buildList<Pair<String, String?>> {
                add(notebook.title to null)
                notebook.allSections()
                    .filter { candidate -> candidate.id != section.id && notebook.breadcrumb(candidate.id).none { it.id == section.id } }
                    .forEach { candidate -> add(notebook.breadcrumb(candidate.id).joinToString(" / ") { it.title } to candidate.id) }
            }
        val currentParentId = notebook.breadcrumb(section.id).dropLast(1).lastOrNull()?.id
        var selectedIndex = candidates.indexOfFirst { it.second == currentParentId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("移动或复制“${section.title}”")
            .setSingleChoiceItems(candidates.map { it.first }.toTypedArray(), selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton("移动") { _, _ ->
                if (notebook.moveSectionToParent(section.id, candidates[selectedIndex].second)) finishNavigationMutation()
            }
            .setNeutralButton("复制") { _, _ ->
                val copied = notebook.copySectionToParent(section.id, candidates[selectedIndex].second)
                if (copied != null) {
                    copied.pages.forEach { copyPageRuntimeAndData(notebook.id, it.source, notebook.id, it.copy) }
                    finishNavigationMutation()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSectionMergeDialog(notebook: NotebookWorkspace, section: SectionNode) {
        val candidates =
            notebook.allSections()
                .filter { candidate -> candidate.id != section.id && notebook.breadcrumb(candidate.id).none { it.id == section.id } }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "没有可合并的其他分区", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("与其他分区合并")
            .setItems(candidates.map { notebook.breadcrumb(it.id).joinToString(" / ") { node -> node.title } }.toTypedArray()) { _, which ->
                val target = candidates[which]
                AlertDialog.Builder(this)
                    .setTitle("合并分区？")
                    .setMessage("“${section.title}”中的页面和子分区将移入“${target.title}”，原分区会被删除。")
                    .setPositiveButton("合并") { _, _ ->
                        if (notebook.mergeSectionInto(section.id, target.id)) finishNavigationMutation()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPageMoveOrCopyDialog(notebook: NotebookWorkspace, sourceSectionId: String, page: NotePage) {
        val candidates = notebook.allSections()
        if (candidates.isEmpty()) return
        var selectedIndex = candidates.indexOfFirst { it.id == sourceSectionId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("移动或复制“${page.title}”")
            .setSingleChoiceItems(
                candidates.map { notebook.breadcrumb(it.id).joinToString(" / ") { node -> node.title } }.toTypedArray(),
                selectedIndex,
            ) { _, which -> selectedIndex = which }
            .setPositiveButton("移动") { _, _ ->
                if (notebook.movePage(sourceSectionId, page.id, candidates[selectedIndex].id)) finishNavigationMutation()
            }
            .setNeutralButton("复制") { _, _ ->
                val copied = notebook.copyPage(sourceSectionId, page.id, candidates[selectedIndex].id)
                if (copied != null) {
                    copyPageRuntimeAndData(notebook.id, copied.source, notebook.id, copied.copy)
                    finishNavigationMutation()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pastePageAfter(targetNotebook: NotebookWorkspace, targetSectionId: String, targetPageId: String) {
        val clipboard = pageNavigationClipboard ?: return
        val sourceNotebook = notebookLibrary.notebooks.firstOrNull { it.id == clipboard.notebookId } ?: return
        val sourcePage = sourceNotebook.page(clipboard.sectionId, clipboard.pageId) ?: run {
            pageNavigationClipboard = null
            return
        }
        val targetIndex = targetNotebook.section(targetSectionId)?.pages?.indexOfFirst { it.id == targetPageId }?.plus(1) ?: return
        if (clipboard is PageNavigationClipboard.Cut && sourceNotebook === targetNotebook) {
            if (targetNotebook.movePage(clipboard.sectionId, clipboard.pageId, targetSectionId, targetIndex)) {
                pageNavigationClipboard = null
                finishNavigationMutation()
            }
            return
        }
        val copied = targetNotebook.copyPageFrom(sourcePage, targetSectionId, targetIndex) ?: return
        copyPageRuntimeAndData(sourceNotebook.id, sourcePage, targetNotebook.id, copied.copy)
        if (clipboard is PageNavigationClipboard.Cut) {
            rehomeSharedPdfSource(sourceNotebook, sourcePage)
            sourceNotebook.deletePage(clipboard.sectionId, clipboard.pageId)
            removePageRuntimeAndData(sourceNotebook.id, clipboard.pageId)
            pageNavigationClipboard = null
        }
        notebookLibrary.selectNotebook(targetNotebook.id)
        finishNavigationMutation()
    }

    private fun copyPageRuntimeAndData(
        sourceNotebookId: String,
        sourcePage: NotePage,
        targetNotebookId: String,
        targetPage: NotePage,
    ) {
        val sourcePdf = sourcePage.pdfPage
        val extraAssets =
            sourcePdf?.takeIf { it.sourceOwnerPageId != sourcePage.id }?.let { pdf ->
                listOf(File(pageContentRepository.assetDirectory(sourceNotebookId, pdf.sourceOwnerPageId), pdf.sourceAssetName))
            }.orEmpty()
        pageContentRepository.copyPageData(sourceNotebookId, sourcePage.id, targetNotebookId, targetPage.id, extraAssets)
        pageSessions[PageSessionKey(sourceNotebookId, sourcePage.id)]?.let { session ->
            pageSessions[PageSessionKey(targetNotebookId, targetPage.id)] =
                session.copy(
                    strokes = session.strokes.toList(),
                    tags = session.tags.toList(),
                    textBoxes = session.textBoxes.toList(),
                )
        }
    }

    private fun rehomeSharedPdfSource(
        notebook: NotebookWorkspace,
        page: NotePage,
        excludedPageIds: Set<String> = setOf(page.id),
    ) {
        val pdf = page.pdfPage ?: return
        if (pdf.sourceOwnerPageId != page.id) return
        val dependents =
            notebook.allSections()
                .flatMap { it.pages }
                .filter { candidate -> candidate.id !in excludedPageIds && candidate.pdfPage?.sourceOwnerPageId == page.id }
        val newOwner = dependents.firstOrNull() ?: return
        val source = File(pageContentRepository.assetDirectory(notebook.id, page.id), pdf.sourceAssetName)
        if (source.isFile) source.copyTo(File(pageContentRepository.assetDirectory(notebook.id, newOwner.id), source.name), overwrite = true)
        dependents.forEach { dependent ->
            dependent.pdfPage = dependent.pdfPage?.copy(sourceOwnerPageId = newOwner.id)
        }
    }

    private fun removePageRuntimeAndData(notebookId: String, pageId: String) {
        val key = PageSessionKey(notebookId, pageId)
        pageSessions.remove(key)
        if (boundPageKey == key) boundPageKey = null
        pageContentRepository.deletePageData(notebookId, pageId)
    }

    private fun removedPageIds(section: SectionNode): List<String> =
        buildList {
            fun visit(node: SectionNode) {
                addAll(node.pages.map { it.id })
                node.children.forEach(::visit)
            }
            visit(section)
        }

    private fun finishNavigationMutation() {
        persistWorkspacePreferences()
        persistPageViewSettings()
        clearContentSelection()
        renderWorkspace()
    }

    private fun copyNavigationLink(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
    }

    private fun sectionLink(notebookId: String, sectionId: String): String =
        Uri.Builder().scheme("mojian").authority("open").appendPath("notebook").appendPath(notebookId)
            .appendPath("section").appendPath(sectionId).build().toString()

    private fun pageLink(notebookId: String, sectionId: String, pageId: String): String =
        Uri.parse(sectionLink(notebookId, sectionId)).buildUpon().appendPath("page").appendPath(pageId).build().toString()

    private fun bindSelectedPageContent() {
        val pageId = workspace.selectedPageId.takeIf(String::isNotEmpty)
        val nextKey = pageId?.let { PageSessionKey(workspace.id, it) }
        if (nextKey != boundPageKey) {
            cancelStylusAction()
            saveBoundPageSession()
            boundPageKey = nextKey
            val session = nextKey?.let(pageSessions::get)
            val stored = nextKey?.let { pageContentRepository.load(it.notebookId,it.pageId).strokes }
            val restoredShapes = stored?.let { array -> (0 until array.length()).mapNotNull { ShapeStrokeCodec.decode(array.getJSONObject(it),::displayColor) } }.orEmpty()
            restoringPageInk = true
            try { dryInkView.replaceStrokes(session?.strokes ?: restoredShapes) } finally { restoringPageInk = false }
            tagLayer.replaceTags(session?.tags.orEmpty())
            textInputLayer.replaceEditors(session?.textBoxes.orEmpty())
            if (session == null) viewport.reset() else viewport.restore(session.viewport)
            applyViewportToLayers()
        }
        val document = pageId?.let { pageContentRepository.load(workspace.id, it) }
        insertContentLayer.bind(document)
        expandPageIfNeeded()
        if (splitViewState.enabled) splitViewState.updateFocused(notebookLibrary.captureLocation())
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
            add(FullPageToolDescriptor("draw:redo", "重做", "arrow_redo"))
            toolShelf.tools.forEach { tool ->
                val icon =
                    when (tool.kind) {
                        DrawingToolKind.PEN -> "pen"
                        DrawingToolKind.HIGHLIGHTER -> "highlight"
                        DrawingToolKind.ERASER -> "eraser"
                        DrawingToolKind.SHAPE -> "shapes"
                        DrawingToolKind.LASSO -> "lasso"
                    }
                add(FullPageToolDescriptor("tool:${tool.id}", tool.displayName, icon))
            }
            add(FullPageToolDescriptor("draw:add", "添加绘图工具", "add"))
            add(FullPageToolDescriptor("draw:pan", "触控笔抓手", "hand_left"))
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
            "draw:redo" ->
                commandButton("arrow_redo", "重做", false) {
                    clearContentSelection()
                    dryInkView.redo()
                }
            "draw:add" -> commandButton("add", "添加绘图工具", false) { anchor -> showAddToolMenu(anchor) }
            "draw:pan" ->
                commandButton("hand_left", "触控笔抓手", singleFingerPanEnabled) { anchor -> handlePanoramaToolClick(anchor) }
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
        row.addView(
            commandButton("arrow_redo", "重做", selected = false) {
                clearContentSelection()
                dryInkView.redo()
            },
            LinearLayout.LayoutParams(dp(40), dp(42)),
        )
        row.addView(verticalDivider())
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
        toolShelf.tools.filter { it.kind == DrawingToolKind.SHAPE }.forEach { row.addView(toolButton(it)) }
        row.addView(verticalDivider())
        row.addView(
            commandButton("hand_left", "触控笔抓手", selected = singleFingerPanEnabled) { anchor -> handlePanoramaToolClick(anchor) },
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
        val tool = effectiveDrawingTool()
        toolDetailRow.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val iconName =
                    when (tool.kind) {
                        DrawingToolKind.PEN -> "pen"
                        DrawingToolKind.HIGHLIGHTER -> "highlight"
                        DrawingToolKind.ERASER -> "eraser"
                        DrawingToolKind.SHAPE -> "shapes"
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
                    parameterControl("大小", eraserRadiusToStep(tool.eraserRadiusMm), ERASER_RADIUS_STEPS, { eraserRadiusLabel(stepToEraserRadius(it)) }) {
                        tool.eraserRadiusMm = stepToEraserRadius(it)
                        persistToolShelf()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                addToolDetailLine(
                    detailChip(if (tool.pressureEraseEnabled) "书写压感切换 开" else "书写压感切换 关", tool.pressureEraseEnabled) {
                        tool.pressureEraseEnabled = !tool.pressureEraseEnabled
                        persistToolShelf()
                        syncPressureEraserWithTool()
                        renderToolDetails()
                    },
                )
                toolDetailRow.addView(
                    parameterControl("按压力度", (tool.pressureEraseThresholdPercent - 40).coerceIn(0, 60), 60, { "${it + 40}%" }) {
                        tool.pressureEraseThresholdPercent = it + 40
                        persistToolShelf()
                        syncPressureEraserWithTool()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                toolDetailRow.addView(
                    parameterControl("按压时间", ((tool.pressureEraseDwellMillis - 50) / 10).toInt().coerceIn(0, 45), 45, { "${50 + it * 10} ms" }) {
                        tool.pressureEraseDwellMillis = (50 + it * 10).toLong()
                        persistToolShelf()
                        syncPressureEraserWithTool()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                addToolDetailLine(detailChip("整笔擦除", true) { }, detailHint("像素擦除稍后接入"))
            }

            DrawingToolKind.SHAPE -> {
                addPaletteControls(tool)
                ShapeKind.entries.chunked(3).forEach { kinds ->
                    addToolDetailLine(*kinds.map { kind -> detailChip(kind.label, tool.shapeKind == kind) { tool.shapeKind = kind; persistToolShelf(); renderToolDetails() } }.toTypedArray())
                }
                toolDetailRow.addView(parameterControl("线宽", ((tool.widthMm*10).roundToInt()-1).coerceIn(0,19), 19, { "${(it+1)/10f} mm" }) { tool.widthMm=(it+1)/10f; persistToolShelf() }, LinearLayout.LayoutParams(MATCH,dp(44)))
                addToolDetailLine(detailChip("停笔转直线 ${if (shapeSettings.holdToStraightenEnabled) "开" else "关"}", shapeSettings.holdToStraightenEnabled) { shapeSettings.holdToStraightenEnabled=!shapeSettings.holdToStraightenEnabled; persistToolShelf(); renderToolDetails() })
                toolDetailRow.addView(parameterControl("等待时间", ((shapeSettings.straightenDelayMillis-200)/50).toInt().coerceIn(0,36), 36, { "${200+it*50} ms" }) { shapeSettings.straightenDelayMillis=200L+it*50; persistToolShelf() }, LinearLayout.LayoutParams(MATCH,dp(44)))
            }
            DrawingToolKind.LASSO -> {
                addToolDetailLine(
                    detailChip("自由圈选", tool.lassoShape == LassoShape.FREEFORM) {
                        tool.lassoShape = LassoShape.FREEFORM
                        persistToolShelf()
                        renderToolDetails()
                    },
                    detailChip("矩形", tool.lassoShape == LassoShape.RECTANGLE) {
                        tool.lassoShape = LassoShape.RECTANGLE
                        persistToolShelf()
                        renderToolDetails()
                    },
                )
                addToolDetailLine(detailHint("选择笔迹、标记、文字框和图片"))
                addToolDetailLine(detailHint("Backspace / Delete 删除所选；在框内按住拖动；拖动控点缩放"))
            }
        }

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

    private fun showPanoramaDetails(anchor: View) {
        toolDetailPopup?.dismiss()
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(
                    TextView(this@MainActivity).apply {
                        text = "全景手柄"
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(MATCH, dp(38)),
                )
                addView(horizontalDivider(), LinearLayout.LayoutParams(MATCH, dp(1)))
                addView(
                    parameterControl(
                        "移动灵敏度",
                        ((panoramaSensitivity * 100).roundToInt() - 25).coerceIn(0, 275),
                        275,
                        { "${it + 25}%" },
                    ) {
                        panoramaSensitivity = ((it + 25) / 100f).coerceIn(MINIMUM_PANORAMA_SENSITIVITY, MAXIMUM_PANORAMA_SENSITIVITY)
                        persistWorkspacePreferences()
                    },
                    LinearLayout.LayoutParams(MATCH, dp(44)),
                )
                addView(
                    detailHint("同时作用于全景手柄和中键临时平移"),
                    LinearLayout.LayoutParams(MATCH, dp(36)),
                )
            }
        val popup =
            PopupWindow(content, dp(316), WRAP, true).apply {
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

    private fun effectiveDrawingTool(): ToolPreset =
        if (pressureEraser.isErasing) toolShelf.pressureEraser else toolShelf.selected

    private fun eraserRadiusToStep(radiusMm: Float): Int =
        ((radiusMm.coerceIn(ToolPreset.MINIMUM_ERASER_RADIUS_MM, ToolPreset.MAXIMUM_ERASER_RADIUS_MM) - ToolPreset.MINIMUM_ERASER_RADIUS_MM) * 4f)
            .roundToInt()
            .coerceIn(0, ERASER_RADIUS_STEPS)

    private fun stepToEraserRadius(step: Int): Float =
        (ToolPreset.MINIMUM_ERASER_RADIUS_MM + step.coerceIn(0, ERASER_RADIUS_STEPS) / 4f)
            .coerceAtMost(ToolPreset.MAXIMUM_ERASER_RADIUS_MM)

    private fun eraserRadiusLabel(radiusMm: Float): String =
        String.format(Locale.ROOT, if (radiusMm < 1f) "%.2f mm" else "%.1f mm", radiusMm)

    @SuppressLint("ClickableViewAccessibility")
    private fun bindInkPipeline() {
        predictor = MotionEventPredictor.newInstance(wetInkView)
        wetInkView.addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    dryInkView.addStrokes(strokes.values)
                    dryInkView.invalidate()
                    expandPageIfNeeded()
                    wetInkView.removeFinishedStrokes(strokes.keys)
                }
            },
        )
        wetInkView.setOnTouchListener { _, event ->
            handleMotionEvent(event)
            true
        }
        wetInkView.setOnHoverListener { _, event ->
            rememberStylusCanvasPoint(event)
            val hoverToolType = event.getToolType(event.actionIndex.coerceIn(0, event.pointerCount - 1))
            val hoverDevice =
                when {
                    isStylus(hoverToolType) -> DirectInputDevice.PEN
                    hoverToolType == MotionEvent.TOOL_TYPE_MOUSE -> DirectInputDevice.MOUSE
                    else -> DirectInputDevice.FINGER
                }
            if (insertSpaceModeEnabled && DirectInputPolicy.showsInsertSpaceHover(hoverDevice)) {
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
        rememberStylusCanvasPoint(event)
        if (handleXiaomiSecondaryStylusEvent(event)) return
        if (handlePanoramaStylusGesture(event)) return
        if (insertSpaceModeEnabled && handleInsertSpaceGesture(event)) return
        if (handleMouseGesture(event)) return
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

    private fun handlePanoramaStylusGesture(event: MotionEvent): Boolean {
        if (!singleFingerPanEnabled || !hasStylusPointer(event)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (!isStylus(event.getToolType(index))) return false
                panoramaPointerId = event.getPointerId(index)
                beginSingleFingerPan(event, index)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = panoramaPointerId?.let(event::findPointerIndex) ?: -1
                if (index >= 0) applySingleFingerPan(event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == panoramaPointerId) {
                    panoramaPointerId = null
                    endTransformGesture()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                panoramaPointerId = null
                endTransformGesture()
            }
        }
        return true
    }

    private fun beginStylusAction(event: MotionEvent) {
        val pointerIndex = event.actionIndex
        if (!isStylus(event.getToolType(pointerIndex))) return
        val pointerId = event.getPointerId(pointerIndex)
        textInputLayer.abandonTemporaryEditors()
        pressurePointerId = pointerId
        lastPressure = event.getPressure(pointerIndex)
        lastPressurePoint = viewport.viewToDocument(event.getX(pointerIndex), event.getY(pointerIndex))
        val hardwareEraser = event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER
        val directPreset = if (hardwareEraser) null else toolShelf.selected
        activeDirectTool = if (hardwareEraser) DrawingToolKind.ERASER else directPreset?.kind ?: DrawingToolKind.PEN

        when (activeDirectTool) {
            DrawingToolKind.PEN,
            DrawingToolKind.HIGHLIGHTER -> {
                shapeStart = ShapePoint(lastPressurePoint.x,lastPressurePoint.y)
                shapeEnd = shapeStart
                shapePreset = directPreset?.copy()
                if (shapeSettings.holdToStraightenEnabled) { straighten.begin(ShapePoint(event.getX(pointerIndex),event.getY(pointerIndex)),event.eventTime); mainHandler.postDelayed(straightenTick,30L) }
                pressureEraser.enabled = PressureEraserPolicy.canTrigger(requireNotNull(activeDirectTool), toolShelf.pressureEraser.pressureEraseEnabled)
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
                eraseAt(lastPressurePoint, pressureEraserRadius())
            }

            DrawingToolKind.LASSO -> {
                beginContentLasso(event.getX(pointerIndex), event.getY(pointerIndex))
            }
            DrawingToolKind.SHAPE -> Unit
            null -> Unit
        }
    }

    private fun continueStylusAction(event: MotionEvent) {
        val pressureIndex = pressurePointerId?.let(event::findPointerIndex) ?: -1
        if (pressureIndex < 0) return
        lastPressure = event.getPressure(pressureIndex)
        lastPressurePoint = viewport.viewToDocument(event.getX(pressureIndex), event.getY(pressureIndex))

        when (activeDirectTool) {
            DrawingToolKind.ERASER -> eraseAt(lastPressurePoint, pressureEraserRadius())
            DrawingToolKind.LASSO -> dryInkView.extendLasso(lastPressurePoint)
            DrawingToolKind.PEN,
            DrawingToolKind.HIGHLIGHTER -> {
                shapeEnd = ShapePoint(lastPressurePoint.x,lastPressurePoint.y)
                if (straighten.snapped) { updateShapePreview(ShapeKind.LINE); return }
                straighten.move(ShapePoint(event.getX(pressureIndex),event.getY(pressureIndex)),event.eventTime,2.5f*resources.displayMetrics.density)
                if (pressureEraser.isErasing) {
                    eraseAt(lastPressurePoint, pressureEraserRadius())
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

            DrawingToolKind.SHAPE -> Unit
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
                val snapped = straighten.snapped
                if (snapped && !wasErasing) {
                    val point = viewport.viewToDocument(event.getX(event.actionIndex),event.getY(event.actionIndex)); shapeEnd=ShapePoint(point.x,point.y)
                    commitShape(ShapeKind.LINE)
                }
                cancelShapeGesture()
                if (!wasErasing && strokeId != null) wetInkView.finishStroke(event, pointerId, strokeId)
                mainHandler.removeCallbacks(activatePressureEraser)
                pressureEraser.onStylusLift(completedInk = !wasErasing && (strokeId != null || snapped))
                dryInkView.setEraserCursor(null)
                if (wasErasing) {
                    renderToolShelf()
                    renderToolDetails()
                }
            }

            DrawingToolKind.LASSO -> {
                finishContentLasso()
            }

            DrawingToolKind.ERASER -> {
                pressureEraser.cancelCandidate()
                dryInkView.setEraserCursor(null)
            }

            DrawingToolKind.SHAPE -> Unit
            null -> Unit
        }
        pressurePointerId = null
        activeDirectTool = null
        syncPressureEraserWithTool()
    }

    private fun cancelStylusAction() {
        val wasErasing = pressureEraser.isErasing
        cancelShapeGesture()
        mainHandler.removeCallbacks(activatePressureEraser)
        pressureEraser.cancelGesture()
        pressurePointerId = null
        activeDirectTool = null
        dryInkView.setEraserCursor(null)
        wetInkView.cancelUnfinishedStrokes()
        activeStrokes.clear()
        if (wasErasing) {
            renderToolShelf()
            renderToolDetails()
        }
        syncPressureEraserWithTool()
    }

    private fun updateShapePreview(kind: ShapeKind) {
        val preset = shapePreset ?: return
        val opacity = if (preset.kind == DrawingToolKind.HIGHLIGHTER) preset.opacityPercent else 100
        val brush = shapeBrush ?: ShapeStrokeCodec.brush(preset.color, preset.widthMm, opacity, displayColor(preset.color)).also { shapeBrush = it }
        dryInkView.shapePreview = ShapeStrokeCodec.create(ShapeGeometry.points(kind,shapeStart,shapeEnd),brush)
    }

    private fun commitShape(kind: ShapeKind) {
        if (kotlin.math.hypot(shapeEnd.x-shapeStart.x,shapeEnd.y-shapeStart.y)<2f) return
        updateShapePreview(kind)
        dryInkView.shapePreview?.let { dryInkView.addStrokes(listOf(it)) }
        dryInkView.shapePreview = null
        saveBoundPageSession()
    }

    private fun cancelShapeGesture() {
        mainHandler.removeCallbacks(straightenTick)
        straighten.cancel()
        shapePointerId=null
        shapePreset=null
        shapeBrush=null
        if (::dryInkView.isInitialized) dryInkView.shapePreview=null
    }

    private fun handleShapePointer(event: MotionEvent): Boolean {
        if (workspace.selectedPage()==null || event.getToolType(0)==MotionEvent.TOOL_TYPE_ERASER) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelStylusAction()
                clearContentSelection()
                textInputLayer.abandonTemporaryEditors()
                textInputLayer.endEditing()
                shapePointerId=event.getPointerId(event.actionIndex)
                shapePreset=toolShelf.selected.copy()
                val p=viewport.viewToDocument(event.x,event.y); shapeStart=ShapePoint(p.x,p.y); shapeEnd=shapeStart
            }
            MotionEvent.ACTION_MOVE -> {
                val index=shapePointerId?.let(event::findPointerIndex) ?: return false
                if (index<0) return false
                val p=viewport.viewToDocument(event.getX(index),event.getY(index)); shapeEnd=ShapePoint(p.x,p.y)
                updateShapePreview(shapePreset?.shapeKind ?: ShapeKind.LINE)
            }
            MotionEvent.ACTION_UP -> {
                if (shapePointerId==null) return false
                val p=viewport.viewToDocument(event.x,event.y); shapeEnd=ShapePoint(p.x,p.y)
                commitShape(shapePreset?.shapeKind ?: ShapeKind.LINE)
                cancelShapeGesture()
            }
            MotionEvent.ACTION_CANCEL -> cancelShapeGesture()
            else -> return shapePointerId!=null
        }
        return true
    }

    private fun persistGeneratedShapes() {
        val key=boundPageKey ?: return
        if (restoringPageInk) return
        val document=insertContentLayer.currentDocument() ?: return
        if (document.notebookId!=key.notebookId || document.pageId!=key.pageId) return
        val retained=org.json.JSONArray()
        for (i in 0 until document.strokes.length()) {
            val stroke=document.strokes.getJSONObject(i)
            if (stroke.optString("brushProfile")!="shape-v1") retained.put(stroke)
        }
        dryInkView.snapshotStrokes().forEach { stroke -> ShapeStrokeCodec.encode(stroke)?.let(retained::put) }
        if (document.strokes.toString() == retained.toString()) return
        document.strokes=retained
        pageContentRepository.save(document)
    }

    private fun selectTool(id: String) {
        endSpringLoadedPanorama()
        if (!toolShelf.select(id)) return
        cancelStylusAction()
        if (toolShelf.selected.kind == DrawingToolKind.PEN) lastPenToolId = toolShelf.selected.id
        persistToolShelf()
        tagLayer.cancelPlacement()
        textInputLayer.abandonTemporaryEditors()
        singleFingerPanEnabled = false
        insertSpaceModeEnabled = false
        insertSpaceActive = false
        insertSpacePointerId = null
        dryInkView.setInsertSpaceHoverY(null)
        dryInkView.setInsertSpaceGuide(null, null)
        transformGestureActive = false
        toolDetailPopup?.dismiss()
        toolDetailPopup = null
        mainHandler.removeCallbacks(activatePressureEraser)
        pressureEraser.cancelCandidate()
        dryInkView.setEraserCursor(null)
        rebuildBrush()
        updateSelectionInteraction()
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
        val eraser = toolShelf.pressureEraser
        pressureEraser.pressureThreshold = eraser.pressureEraseThresholdPercent / 100f
        pressureEraser.dwellMillis = eraser.pressureEraseDwellMillis
        pressureEraser.enabled = PressureEraserPolicy.canTrigger(toolShelf.selected.kind, eraser.pressureEraseEnabled)
        if (!pressureEraser.enabled) {
            mainHandler.removeCallbacks(activatePressureEraser)
            pressureEraser.cancelCandidate()
        }
    }

    private fun isStylus(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    private fun MotionEvent.hasCtrlModifier(): Boolean = metaState and KeyEvent.META_CTRL_ON != 0

    private fun MotionEvent.hasShiftModifier(): Boolean = metaState and KeyEvent.META_SHIFT_ON != 0

    private fun hasStylusPointer(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { isStylus(event.getToolType(it)) }

    private fun handleFingerGesture(event: MotionEvent): Boolean {
        if (hasStylusPointer(event) || activeStrokes.isNotEmpty()) return false
        val fingerIndices = (0 until event.pointerCount).filter { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
        if (fingerIndices.size < 2 && !transformGestureActive) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (fingerIndices.size >= 2) {
                beginTransformGesture(event, fingerIndices)
            }
            MotionEvent.ACTION_MOVE -> {
                if (fingerIndices.size >= 2) {
                    val (focusX, focusY, span) = gestureGeometry(event, fingerIndices)
                    if (!transformGestureActive) {
                        beginTransformGesture(event, fingerIndices)
                    } else {
                        transformGestureArbiter
                            .update(focusX, focusY, span, allowScale = !zoomLocked)
                            ?.let { update ->
                                viewport.applyGesture(
                                    previousFocusX = update.previousFocusX,
                                    previousFocusY = update.previousFocusY,
                                    focusX = update.focusX,
                                    focusY = update.focusY,
                                    requestedScaleFactor = update.scaleFactor,
                                )
                                applyViewportToLayers()
                            }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = fingerIndices.filter { it != event.actionIndex }
                when {
                    remaining.size >= 2 -> beginTransformGesture(event, remaining)
                    else -> endTransformGesture()
                }
            }
            MotionEvent.ACTION_UP -> {
                endTransformGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                endTransformGesture()
            }
        }
        return true
    }

    private fun handleMouseGesture(event: MotionEvent): Boolean {
        val mouseIndex = (0 until event.pointerCount).firstOrNull { event.getToolType(it) == MotionEvent.TOOL_TYPE_MOUSE }
            ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val middlePressed = event.buttonState and MotionEvent.BUTTON_TERTIARY != 0
                mouseCanvasGesture =
                    CanvasInputRouting.mouseDragIntent(
                        middlePressed = middlePressed,
                        ctrlPressed = event.hasCtrlModifier(),
                    )
                if (mouseCanvasGesture != CanvasDragIntent.NONE) beginSpringLoadedPanorama()
                when (mouseCanvasGesture) {
                    CanvasDragIntent.PAN -> beginSingleFingerPan(event, mouseIndex)
                    CanvasDragIntent.ZOOM -> lastMouseGestureY = event.getY(mouseIndex)
                    CanvasDragIntent.NONE -> Unit
                }
            }
            MotionEvent.ACTION_MOVE -> when (mouseCanvasGesture) {
                CanvasDragIntent.PAN -> applySingleFingerPan(event.getX(mouseIndex), event.getY(mouseIndex))
                CanvasDragIntent.ZOOM -> {
                    val x = event.getX(mouseIndex)
                    val y = event.getY(mouseIndex)
                    val factor = kotlin.math.exp((lastMouseGestureY - y) / dp(240).toFloat())
                    viewport.applyGesture(x, y, x, y, factor)
                    lastMouseGestureY = y
                    applyViewportToLayers()
                }
                CanvasDragIntent.NONE -> Unit
            }
            MotionEvent.ACTION_UP -> {
                if (mouseCanvasGesture == CanvasDragIntent.PAN) endTransformGesture()
                mouseCanvasGesture = CanvasDragIntent.NONE
                endSpringLoadedPanorama()
            }
            MotionEvent.ACTION_CANCEL -> {
                endTransformGesture()
                mouseCanvasGesture = CanvasDragIntent.NONE
                endSpringLoadedPanorama()
            }
        }
        return true
    }

    private fun beginContentLasso(viewX: Float, viewY: Float, shape: LassoShape = toolShelf.selected.lassoShape) {
        clearContentSelection()
        dryInkView.beginLasso(viewport.viewToDocument(viewX, viewY), shape)
    }

    private fun beginXiaomiSecondaryPreview(viewX: Float, viewY: Float) {
        dryInkView.beginLassoPreview(viewport.viewToDocument(viewX, viewY), LassoShape.FREEFORM)
        xiaomiSecondaryPreviewActive = true
    }

    private fun finishContentLasso() {
        val polygon = dryInkView.finishLasso()
        tagLayer.selectInPolygon(polygon)
        textInputLayer.selectInPolygon(polygon, notifySelectionChange = false)
        insertContentLayer.selectInPolygon(polygon)
        updateSelectionInteraction()
        renderToolDetails()
    }

    private fun cancelDirectMarquee() {
        dryInkView.cancelLassoPreview()
        updateSelectionInteraction()
    }

    private fun rememberStylusCanvasPoint(event: MotionEvent) {
        val index =
            (0 until event.pointerCount).firstOrNull { pointerIndex -> isStylus(event.getToolType(pointerIndex)) }
                ?: return
        lastStylusCanvasPoint = PointF(event.getX(index), event.getY(index))
    }

    private fun handleXiaomiSecondaryStylusEvent(event: MotionEvent): Boolean {
        if (xiaomiSecondaryKey == null) return false
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!isStylus(event.getToolType(actionIndex))) return false
                xiaomiSecondaryPointerId = event.getPointerId(actionIndex)
                lastStylusCanvasPoint = PointF(event.getX(actionIndex), event.getY(actionIndex))
                if (!xiaomiSecondaryPreviewActive && !xiaomiSecondaryCommitted) {
                    beginXiaomiSecondaryPreview(event.getX(actionIndex), event.getY(actionIndex))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex =
                    xiaomiSecondaryPointerId?.let(event::findPointerIndex)?.takeIf { it >= 0 }
                        ?: (0 until event.pointerCount).firstOrNull { isStylus(event.getToolType(it)) }
                        ?: return true
                lastStylusCanvasPoint = PointF(event.getX(pointerIndex), event.getY(pointerIndex))
                if (!xiaomiSecondaryPreviewActive && !xiaomiSecondaryCommitted) {
                    xiaomiSecondaryPointerId = event.getPointerId(pointerIndex)
                    beginXiaomiSecondaryPreview(event.getX(pointerIndex), event.getY(pointerIndex))
                } else if (xiaomiSecondaryPreviewActive) {
                    dryInkView.extendLasso(viewport.viewToDocument(event.getX(pointerIndex), event.getY(pointerIndex)))
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                if (pointerId != xiaomiSecondaryPointerId) return true
                lastStylusCanvasPoint = PointF(event.getX(actionIndex), event.getY(actionIndex))
                if (xiaomiSecondaryPreviewActive) {
                    dryInkView.extendLasso(viewport.viewToDocument(event.getX(actionIndex), event.getY(actionIndex)))
                    val heldMillis = (event.eventTime - xiaomiSecondaryDownAt).coerceAtLeast(0L)
                    if (
                        CanvasInputRouting.stylusShortcutReleaseIntent(heldMillis, xiaomiPenShortcutSettings.holdMillis) ==
                            SecondaryReleaseIntent.COMMIT_LASSO
                    ) {
                        finishContentLasso()
                        xiaomiSecondaryPreviewActive = false
                        xiaomiSecondaryCommitted = true
                    }
                }
                xiaomiSecondaryPointerId = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (xiaomiSecondaryPreviewActive) dryInkView.cancelLassoPreview()
                xiaomiSecondaryPreviewActive = false
                xiaomiSecondaryPointerId = null
            }
        }
        return true
    }

    private fun handleCanvasMousePointerEvent(event: MotionEvent, viewX: Float, viewY: Float): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val action = event.actionMasked
        val pressedButton =
            when (action) {
                MotionEvent.ACTION_BUTTON_PRESS -> event.actionButton
                MotionEvent.ACTION_DOWN -> event.buttonState
                else -> 0
            }
        val releasedButton = if (action == MotionEvent.ACTION_BUTTON_RELEASE) event.actionButton else 0

        if (pressedButton and MotionEvent.BUTTON_TERTIARY != 0) {
            MojianCommandMenu.dismissActive()
            resetMouseSecondaryGesture(cancelPreview = true)
            mouseCanvasGesture = CanvasInputRouting.mouseDragIntent(middlePressed = true, ctrlPressed = event.hasCtrlModifier())
            beginSpringLoadedPanorama()
            if (mouseCanvasGesture == CanvasDragIntent.PAN) {
                transformGestureActive = true
                transformGestureArbiter.cancel()
                lastGestureFocusX = viewX
                lastGestureFocusY = viewY
            } else {
                lastMouseGestureY = viewY
            }
            return true
        }
        if (pressedButton and MotionEvent.BUTTON_SECONDARY != 0) {
            if (textInputLayer.isEditingAt(viewX, viewY)) return false
            MojianCommandMenu.dismissActive()
            if (mouseCanvasGesture != CanvasDragIntent.NONE) endMouseCanvasGesture()
            resetMouseSecondaryGesture(cancelPreview = true)
            mouseSecondaryGesture = MouseSecondaryGesture.PENDING
            mouseSecondaryStart = PointF(viewX, viewY)
            return true
        }

        if (action == MotionEvent.ACTION_MOVE && mouseCanvasGesture != CanvasDragIntent.NONE) {
            when (mouseCanvasGesture) {
                CanvasDragIntent.PAN -> applySingleFingerPan(viewX, viewY)
                CanvasDragIntent.ZOOM -> {
                    val factor = kotlin.math.exp((lastMouseGestureY - viewY) / dp(240).toFloat())
                    viewport.applyGesture(viewX, viewY, viewX, viewY, factor)
                    lastMouseGestureY = viewY
                    applyViewportToLayers()
                }
                CanvasDragIntent.NONE -> Unit
            }
            return true
        }
        if (
            mouseCanvasGesture != CanvasDragIntent.NONE &&
            (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || releasedButton and MotionEvent.BUTTON_TERTIARY != 0)
        ) {
            endMouseCanvasGesture()
            return true
        }

        if (action == MotionEvent.ACTION_MOVE && mouseSecondaryGesture != MouseSecondaryGesture.NONE) {
            if (
                mouseSecondaryGesture == MouseSecondaryGesture.PENDING &&
                CanvasInputRouting.mouseSecondaryStartsLasso(
                    viewX - mouseSecondaryStart.x,
                    viewY - mouseSecondaryStart.y,
                    mouseSecondarySlop,
                )
            ) {
                beginContentLasso(mouseSecondaryStart.x, mouseSecondaryStart.y, LassoShape.FREEFORM)
                mouseSecondaryGesture = MouseSecondaryGesture.LASSO
            }
            if (mouseSecondaryGesture == MouseSecondaryGesture.LASSO) {
                dryInkView.extendLasso(viewport.viewToDocument(viewX, viewY))
            }
            return true
        }
        if (
            mouseSecondaryGesture != MouseSecondaryGesture.NONE &&
            (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || releasedButton and MotionEvent.BUTTON_SECONDARY != 0)
        ) {
            val completed = action != MotionEvent.ACTION_CANCEL
            when (mouseSecondaryGesture) {
                MouseSecondaryGesture.PENDING -> if (completed) showCanvasContextMenuAt(viewX, viewY)
                MouseSecondaryGesture.LASSO -> if (completed) {
                    dryInkView.extendLasso(viewport.viewToDocument(viewX, viewY))
                    finishContentLasso()
                } else {
                    dryInkView.cancelLassoPreview()
                }
                MouseSecondaryGesture.NONE -> Unit
            }
            resetMouseSecondaryGesture(cancelPreview = false)
            return true
        }
        return false
    }

    private fun endMouseCanvasGesture() {
        if (mouseCanvasGesture == CanvasDragIntent.PAN) endTransformGesture()
        mouseCanvasGesture = CanvasDragIntent.NONE
        endSpringLoadedPanorama()
    }

    private fun resetMouseSecondaryGesture(cancelPreview: Boolean) {
        if (cancelPreview && mouseSecondaryGesture == MouseSecondaryGesture.LASSO && ::dryInkView.isInitialized) {
            dryInkView.cancelLassoPreview()
        }
        mouseSecondaryGesture = MouseSecondaryGesture.NONE
    }

    private fun handleCanvasMenuPointerEvent(event: MotionEvent): Boolean {
        val location = IntArray(2)
        paperContainer.getLocationOnScreen(location)
        return handleCanvasMousePointerEvent(event, event.rawX - location[0], event.rawY - location[1])
    }

    private fun observeCanvasPointerEvent(event: MotionEvent): Boolean {
        rememberStylusCanvasPoint(event)
        if (handleXiaomiSecondaryStylusEvent(event)) return true
        val mouseIndex = (0 until event.pointerCount).firstOrNull { event.getToolType(it) == MotionEvent.TOOL_TYPE_MOUSE }
        if (mouseIndex != null && handleCanvasMousePointerEvent(event, event.getX(mouseIndex), event.getY(mouseIndex))) return true
        val fingerCount = (0 until event.pointerCount).count { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
        if (fingerCount >= 2 || directPageGestureCaptured) {
            if (!directPageGestureCaptured) {
                cancelShapeGesture()
                directPageGestureCaptured = true
                selectionOverlay.cancelDirectGesture()
                cancelInsertSpaceGesture()
                textInputLayer.abandonTemporaryEditors()
                textInputLayer.endEditing()
            }
            handleFingerGesture(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                directPageGestureCaptured = false
            }
            return true
        }
        if (toolShelf.selected.kind == DrawingToolKind.SHAPE && !singleFingerPanEnabled && !insertSpaceModeEnabled) {
            val overChrome = event.actionMasked == MotionEvent.ACTION_DOWN && listOf(floatingToolbox, fullPagePaneButtons, fullPageToggle.parent as View).any { control ->
                if (!control.isShown) false else {
                    val rect = android.graphics.Rect(); control.getGlobalVisibleRect(rect)
                    rect.contains(event.rawX.toInt(), event.rawY.toInt())
                }
            }
            if (!overChrome && handleShapePointer(event)) return true
        }
        if (insertSpaceModeEnabled && fingerCount == 1) return handleInsertSpaceGesture(event)
        return false
    }

    private fun selectedContentHitAt(viewX: Float, viewY: Float): Boolean {
        if (textInputLayer.isSelectedAt(viewX, viewY)) return true
        if (tagLayer.isSelectedAtView(viewX, viewY)) return true
        if (insertContentLayer.isSelectedAtView(viewX, viewY)) return true
        val point = viewport.viewToDocument(viewX, viewY)
        return dryInkView.isSelectedAtDocument(point.x, point.y, dp(12).toFloat() / viewport.scale)
    }

    private fun handleDirectTap(viewX: Float, viewY: Float, fromFinger: Boolean) {
        if (textInputLayer.isSelectedAt(viewX, viewY)) {
            textInputLayer.editSelectedAt(viewX, viewY, showIme = fromFinger)
            updateSelectionInteraction()
            return
        }
        if (textInputLayer.hasEditorAt(viewX, viewY)) {
            clearContentSelection()
            textInputLayer.selectAt(viewX, viewY)
            updateSelectionInteraction()
            return
        }
        if (insertContentLayer.hasSelectableAtView(viewX, viewY)) {
            if (!insertContentLayer.isSelectedAtView(viewX, viewY)) {
                clearContentSelection()
                insertContentLayer.selectAtView(viewX, viewY)
            }
            updateSelectionInteraction()
            return
        }
        if (tagLayer.hasTagAtView(viewX, viewY)) {
            if (!tagLayer.isSelectedAtView(viewX, viewY)) {
                clearContentSelection()
                tagLayer.selectAtView(viewX, viewY)
            }
            updateSelectionInteraction()
            return
        }
        val point = viewport.viewToDocument(viewX, viewY)
        val hitRadius = dp(12).toFloat() / viewport.scale
        if (dryInkView.hasStrokeAtDocument(point.x, point.y, hitRadius)) {
            if (!dryInkView.isSelectedAtDocument(point.x, point.y, hitRadius)) {
                clearContentSelection()
                dryInkView.selectAtDocument(point.x, point.y, hitRadius)
            }
            updateSelectionInteraction()
            return
        }
        clearContentSelection()
        textInputLayer.createTemporaryEditor(viewX, viewY)
        updateSelectionInteraction()
    }

    private fun handleDirectLongPress(viewX: Float, viewY: Float): Boolean =
        showCanvasContextMenuAt(viewX, viewY, performHaptic = true)

    private fun showCanvasContextMenuAt(viewX: Float, viewY: Float, performHaptic: Boolean = false): Boolean {
        val documentPoint = viewport.viewToDocument(viewX, viewY)
        val selectionBounds = combinedSelectionBounds()
        if (selectionBounds?.contains(documentPoint.x, documentPoint.y) == true) {
            val onlyInsertObjectSelected =
                insertContentLayer.selectedCount() == 1 &&
                    dryInkView.selectedCount() == 0 &&
                    tagLayer.selectedCount() == 0 &&
                    textInputLayer.selectedCount() == 0
            if (onlyInsertObjectSelected && insertContentLayer.hasSelectableAtView(viewX, viewY)) {
                return insertContentLayer.requestContextMenuAt(viewX, viewY)
            }
            showSelectionContextMenuAt(viewX, viewY)
            if (performHaptic) selectionOverlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return true
        }
        if (insertContentLayer.hasSelectableAtView(viewX, viewY)) {
            if (!insertContentLayer.isSelectedAtView(viewX, viewY)) {
                clearContentSelection()
                insertContentLayer.selectAtView(viewX, viewY)
            }
            return insertContentLayer.requestContextMenuAt(viewX, viewY)
        }
        val hitRadius = dp(12).toFloat() / viewport.scale
        when {
            textInputLayer.hasEditorAt(viewX, viewY) -> {
                if (!textInputLayer.isSelectedAt(viewX, viewY)) {
                    clearContentSelection()
                    textInputLayer.selectAt(viewX, viewY)
                }
            }
            tagLayer.hasTagAtView(viewX, viewY) -> {
                if (!tagLayer.isSelectedAtView(viewX, viewY)) {
                    clearContentSelection()
                    tagLayer.selectAtView(viewX, viewY)
                }
            }
            dryInkView.hasStrokeAtDocument(documentPoint.x, documentPoint.y, hitRadius) -> {
                if (!dryInkView.isSelectedAtDocument(documentPoint.x, documentPoint.y, hitRadius)) {
                    clearContentSelection()
                    dryInkView.selectAtDocument(documentPoint.x, documentPoint.y, hitRadius)
                }
            }
            else -> {
                textInputLayer.abandonTemporaryEditors()
                textInputLayer.endEditing()
                clearContentSelection()
                showCanvasBackgroundContextMenuAt(viewX, viewY)
                if (performHaptic) selectionOverlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                return true
            }
        }
        updateSelectionInteraction()
        showSelectionContextMenuAt(viewX, viewY)
        if (performHaptic) selectionOverlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    private fun showSelectionContextMenuAt(viewX: Float, viewY: Float) {
        val location = IntArray(2)
        selectionOverlay.getLocationOnScreen(location)
        MojianCommandMenu.showAt(
            this,
            selectionOverlay,
            location[0] + viewX.roundToInt(),
            location[1] + viewY.roundToInt(),
            listOf(
                MojianCommandMenu.Item("粘贴", "clipboard_paste", enabled = insertObjectClipboard != null, action = ::pasteInsertObject),
                MojianCommandMenu.Item("全选", "lasso", dividerBefore = true, action = ::selectAllContent),
                MojianCommandMenu.Item("删除", "delete_dismiss", destructive = true, action = { deleteContentSelection() }),
            ),
            widthDp = 196,
            pointerButtonHandler = ::handleCanvasMenuPointerEvent,
        )
    }

    private fun showCanvasBackgroundContextMenuAt(viewX: Float, viewY: Float) {
        val location = IntArray(2)
        selectionOverlay.getLocationOnScreen(location)
        MojianCommandMenu.showAt(
            this,
            selectionOverlay,
            location[0] + viewX.roundToInt(),
            location[1] + viewY.roundToInt(),
            listOf(
                MojianCommandMenu.Item("粘贴", "clipboard_paste", enabled = insertObjectClipboard != null, action = ::pasteInsertObject),
                MojianCommandMenu.Item("全选", "lasso", action = ::selectAllContent),
            ),
            widthDp = 196,
            pointerButtonHandler = ::handleCanvasMenuPointerEvent,
        )
    }

    private fun selectAllContent() {
        textInputLayer.abandonTemporaryEditors()
        textInputLayer.endEditing()
        dryInkView.selectAll()
        tagLayer.selectAll()
        textInputLayer.selectAll(notifySelectionChange = false)
        insertContentLayer.selectAll()
        updateSelectionInteraction()
        renderToolDetails()
    }

    private fun beginSingleFingerPan(event: MotionEvent, fingerIndex: Int) {
        transformGestureActive = true
        transformGestureArbiter.cancel()
        lastGestureFocusX = event.getX(fingerIndex)
        lastGestureFocusY = event.getY(fingerIndex)
    }

    private fun applySingleFingerPan(focusX: Float, focusY: Float) {
        val adjusted = CanvasInputRouting.scaledPanFocus(lastGestureFocusX, lastGestureFocusY, focusX, focusY, panoramaSensitivity)
        viewport.applyGesture(
            previousFocusX = lastGestureFocusX,
            previousFocusY = lastGestureFocusY,
            focusX = adjusted.x,
            focusY = adjusted.y,
            requestedScaleFactor = 1f,
        )
        lastGestureFocusX = focusX
        lastGestureFocusY = focusY
        applyViewportToLayers()
    }

    private fun applyViewportToLayers() {
        expandA4IfNeeded()
        dryInkView.setViewport(viewport.documentToViewMatrix())
        pageSurfaceBackground.invalidate()
        pageSurfaceForeground.invalidate()
        insertContentLayer.invalidate()
        textInputLayer.applyViewport()
        tagLayer.invalidate()
        selectionOverlay.invalidate()
        refreshViewRibbonState()
    }

    private fun beginTransformGesture(event: MotionEvent, fingerIndices: List<Int>) {
        val (focusX, focusY, span) = gestureGeometry(event, fingerIndices)
        transformGestureActive = true
        lastGestureFocusX = focusX
        lastGestureFocusY = focusY
        transformGestureArbiter.begin(focusX, focusY, span)
    }

    private fun endTransformGesture() {
        transformGestureActive = false
        transformGestureArbiter.cancel()
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

    private fun pressureEraserRadius(): Float =
        toolShelf.pressureEraser.eraserRadiusMm
            .coerceIn(ToolPreset.MINIMUM_ERASER_RADIUS_MM, ToolPreset.MAXIMUM_ERASER_RADIUS_MM) * INTERNAL_UNITS_PER_MM

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

    private fun selectedContentCount(): Int =
        dryInkView.selectedCount() +
            tagLayer.selectedCount() +
            textInputLayer.selectedCount() +
            insertContentLayer.selectedCount()

    private fun clearContentSelection() {
        dryInkView.clearSelection()
        if (::tagLayer.isInitialized) tagLayer.clearSelection()
        if (::textInputLayer.isInitialized) {
            textInputLayer.endEditing()
            textInputLayer.clearSelection()
        }
        if (::insertContentLayer.isInitialized) insertContentLayer.clearSelection()
        if (::selectionOverlay.isInitialized) updateSelectionInteraction()
    }

    private fun deleteContentSelection(): Int {
        val removed =
            dryInkView.deleteSelection() +
                tagLayer.deleteSelection() +
                textInputLayer.deleteSelection() +
                insertContentLayer.deleteSelection()
        saveBoundPageSession()
        selectionOverlay.invalidate()
        return removed
    }

    private fun combinedSelectionBounds(): RectF? =
        listOfNotNull(
            dryInkView.selectedBounds(),
            tagLayer.selectedBounds(),
            textInputLayer.selectedBounds(),
            insertContentLayer.selectedBounds(),
        ).map(::RectF).reduceOrNull { combined, bounds -> combined.apply { union(bounds) } }

    private fun updateSelectionInteraction() {
        if (!::selectionOverlay.isInitialized) return
        selectionOverlay.selectionEnabled = !tagLayer.isPlacementPending()
        selectionOverlay.fingerDirectInputEnabled = !insertSpaceModeEnabled
        selectionOverlay.invalidate()
    }

    private fun toggleSectionPane() {
        if (fullPageMode) fullPageSectionPaneOpen = !fullPageSectionPaneOpen
        else {
            sectionPaneVisible = !sectionPaneVisible
            persistWorkspacePreferences()
        }
        applyChromeVisibility()
    }

    private fun togglePagePane() {
        if (fullPageMode) fullPagePagePaneOpen = !fullPagePagePaneOpen
        else {
            pagePaneVisible = !pagePaneVisible
            persistWorkspacePreferences()
        }
        applyChromeVisibility()
    }

    private fun setFullPageMode(enabled: Boolean) {
        if (fullPageMode == enabled) return
        fullPageMode = enabled
        if (enabled) {
            fullPageSectionPaneOpen = false
            fullPagePagePaneOpen = false
        }
        applyChromeVisibility()
        refreshViewRibbonState()
    }

    private fun applyChromeVisibility() {
        if (!::ribbonTabs.isInitialized) return
        if (!fullPageMode) drawingToolbarVisible = true
        ribbonTabs.visibility = if (fullPageMode) View.GONE else View.VISIBLE
        drawingRibbon.visibility = if (fullPageMode) View.GONE else View.VISIBLE
        floatingToolbox.visibility = if (fullPageMode && drawingToolbarVisible) View.VISIBLE else View.GONE
        toolbarToggle.visibility = if (fullPageMode) View.VISIBLE else View.GONE
        navigationRail.visibility = if (fullPageMode) View.GONE else View.VISIBLE
        fullPagePaneButtons.visibility = if (fullPageMode) View.VISIBLE else View.GONE
        navigationPane.visibility = if (if (fullPageMode) fullPageSectionPaneOpen else sectionPaneVisible) View.VISIBLE else View.GONE
        pagePane.visibility = if (if (fullPageMode) fullPagePagePaneOpen else pagePaneVisible) View.VISIBLE else View.GONE
        updateToggleVisual(toolbarToggle, drawingToolbarVisible)
        updateToggleVisual(sectionPaneToggle, sectionPaneVisible)
        updateToggleVisual(pagePaneToggle, pagePaneVisible)
        updateToggleVisual(fullPageSectionPaneToggle, fullPageSectionPaneOpen)
        updateToggleVisual(fullPagePagePaneToggle, fullPagePagePaneOpen)
        fullPageToggleIcon.setImageDrawable(
            fluentIcon(if (fullPageMode) "full_screen_minimize" else "full_screen_maximize"),
        )
        fullPageToggleIcon.imageTintList = ColorStateList.valueOf(TEXT_PRIMARY)
        val fullPageDescription = if (fullPageMode) "退出整页视图" else "进入整页视图"
        fullPageToggle.contentDescription = fullPageDescription
        setDarkTooltip(fullPageToggle, fullPageDescription)
    }

    private fun applySelectedPageAppearance() {
        if (!::pageSurfaceBackground.isInitialized) return
        val page = workspace.selectedPage()
        val settings = page?.viewSettings ?: PageViewSettings()
        val nextDark = settings.darkBackground
        darkCanvasActive = nextDark
        dryInkView.backgroundFormat = settings.backgroundFormat
        dryInkView.setDarkBackground(nextDark)
        textInputLayer.setDarkBackground(nextDark)
        canvasBackgroundLayer.updatePage(settings)
        pageSurfaceBackground.updatePage(page?.title.orEmpty(), settings)
        pageSurfaceForeground.updatePage(page?.title.orEmpty(), settings)
        if (::currentBrush.isInitialized) rebuildBrush()
        refreshViewRibbonState()
    }

    private fun updateCurrentPageView(change: (PageViewSettings) -> Unit) {
        if (activeStrokes.isNotEmpty()) return
        val page = workspace.selectedPage() ?: return
        change(page.viewSettings)
        ensureA4Capacity(page)
        ensureBoundlessCapacity(page)
        persistPageViewSettings()
        applySelectedPageAppearance()
        persistWorkspacePreferences()
    }

    private fun ensureA4Capacity(page: NotePage? = workspace.selectedPage()): Boolean {
        val targetPage = page ?: return false
        if (targetPage.viewSettings.paperSize != PaperSize.A4) return false
        val boundDocument = if (::insertContentLayer.isInitialized) insertContentLayer.currentDocument() else null
        val isLivePage = boundDocument?.notebookId == workspace.id && boundDocument.pageId == targetPage.id
        val contentBottom =
            if (isLivePage) {
                maxOf(
                    dryInkView.contentBottom(),
                    tagLayer.contentBottom(),
                    textInputLayer.contentBottom(),
                    insertContentLayer.contentBottom(),
                )
            } else {
                0f
            }
        val visibleDocumentBottom =
            if (isLivePage && ::paperContainer.isInitialized && paperContainer.height > 0) {
                viewport.viewToDocument(0f, paperContainer.height.toFloat()).y
            } else {
                0f
            }
        val required =
            A4PageLayout.requiredPageCount(
                contentBottom,
                visibleDocumentBottom,
                targetPage.viewSettings.a4PageCount,
            )
        if (required == targetPage.viewSettings.a4PageCount) return false
        targetPage.viewSettings.a4PageCount = required
        return true
    }

    private fun documentContentBounds(): Sequence<StrokeViewportBounds> = sequence {
        yieldAll(dryInkView.contentBounds())
        yieldAll(tagLayer.contentBounds())
        yieldAll(textInputLayer.contentBounds())
        yieldAll(insertContentLayer.contentBounds())
    }

    private fun ensureBoundlessCapacity(page: NotePage): Boolean {
        if (page.viewSettings.paperSize != PaperSize.BOUNDLESS || !::insertContentLayer.isInitialized) return false
        val document = insertContentLayer.currentDocument() ?: return false
        if (document.notebookId != workspace.id || document.pageId != page.id) return false
        return BoundlessPageLayout.ensureCapacity(page.viewSettings, documentContentBounds())
    }

    private fun expandPageIfNeeded() {
        val page = workspace.selectedPage() ?: return
        if (!ensureA4Capacity(page) && !ensureBoundlessCapacity(page)) return
        persistPageViewSettings()
        canvasBackgroundLayer.updatePage(page.viewSettings)
        pageSurfaceBackground.updatePage(page.title, page.viewSettings)
        pageSurfaceForeground.updatePage(page.title, page.viewSettings)
    }

    // Browsing may append A4 sheets, but must never grow boundless paper.
    private fun expandA4IfNeeded() {
        if (workspace.selectedPage()?.viewSettings?.paperSize == PaperSize.A4) expandPageIfNeeded()
    }

    private fun refreshViewRibbonState() {
        zoomPercentButton?.text = "${(viewport.scale * 100f).roundToInt()}%"
        zoomLockButton?.let { updateToggleVisual(it, zoomLocked) }
        fullViewButton?.let { updateToggleVisual(it, fullPageMode) }
        normalViewButton?.let { updateToggleVisual(it, !fullPageMode) }
        splitViewButton?.let { updateToggleVisual(it, splitViewState.enabled) }
        val settings = workspace.selectedPage()?.viewSettings
        pageTitleViewButton?.let { updateToggleVisual(it, settings?.pageTitleVisible == false) }
        backgroundViewButton?.let { updateToggleVisual(it, settings?.darkBackground == true) }
        pageColorSwatch?.background =
            rounded(
                settings?.pageColor?.displayArgb(settings.darkBackground) ?: Color.TRANSPARENT,
                dp(1).toFloat(),
                if (settings != null) BORDER_STRONG else Color.TRANSPARENT,
            )
    }

    private fun zoomValueButton(): TextView =
        TextView(this).apply {
            text = "${(viewport.scale * 100f).roundToInt()}%"
            gravity = Gravity.CENTER
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = rounded(Color.rgb(42, 42, 42), dp(2).toFloat(), BORDER_STRONG)
            contentDescription = "显示比例"
            setDarkTooltip(this, "显示比例")
            setOnClickListener { showZoomPresetMenu(this) }
        }

    private fun zoomResetButton(): TextView =
        TextView(this).apply {
            text = "100%"
            gravity = Gravity.CENTER
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            background = toggleBackground(false)
            contentDescription = "恢复 100%"
            setDarkTooltip(this, "恢复 100%")
            setOnClickListener { setZoomScale(1f) }
        }

    private fun pageBoundCommand(
        icon: String,
        label: String,
        selected: Boolean = false,
        action: (View) -> Unit,
    ): View =
        commandButton(icon, label, selected, action = action).apply {
            isEnabled = workspace.selectedPage() != null
            alpha = if (isEnabled) 1f else 0.35f
        }

    private fun pageColorButton(): View =
        pageBoundCommand("paint_brush", "页面颜色") { anchor -> showPageColorMenu(anchor) }.also { button ->
            pageColorSwatch =
                View(this).apply {
                    val settings = workspace.selectedPage()?.viewSettings
                    background =
                        rounded(
                            settings?.pageColor?.displayArgb(settings.darkBackground) ?: Color.TRANSPARENT,
                            dp(1).toFloat(),
                            if (settings != null) BORDER_STRONG else Color.TRANSPARENT,
                        )
                }
            (button as FrameLayout).addView(
                pageColorSwatch,
                FrameLayout.LayoutParams(dp(20), dp(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(3)
                },
            )
        }

    private fun showZoomPresetMenu(anchor: View) {
        val current = (viewport.scale * 100f).roundToInt()
        MojianCommandMenu.show(
            this,
            anchor,
            ZOOM_PRESETS.map { percent ->
                MojianCommandMenu.Item("$percent%", "zoom_in", checked = current == percent, action = { setZoomScale(percent / 100f) })
            },
            widthDp = 170,
        )
    }

    private fun zoomByStep(direction: Int) {
        val current = viewport.scale * 100f
        val target =
            if (direction > 0) ZOOM_PRESETS.firstOrNull { it > current + 0.5f } ?: ZOOM_PRESETS.last()
            else ZOOM_PRESETS.lastOrNull { it < current - 0.5f } ?: ZOOM_PRESETS.first()
        setZoomScale(target / 100f)
    }

    private fun setZoomScale(scale: Float) {
        if (activeStrokes.isNotEmpty()) return
        if (paperContainer.width <= 0 || paperContainer.height <= 0) {
            paperContainer.post { setZoomScale(scale) }
            return
        }
        viewport.setScaleAround(scale, paperContainer.width * 0.5f, paperContainer.height * 0.5f)
        applyViewportToLayers()
    }

    private fun fitPageWidth() {
        val page = workspace.selectedPage() ?: return
        if (activeStrokes.isNotEmpty()) return
        if (paperContainer.width <= 0) {
            paperContainer.post(::fitPageWidth)
            return
        }
        val documentWidth = page.viewSettings.documentWidth ?: return
        viewport.fitDocumentWidth(
            documentLeft = 0f,
            documentWidth = documentWidth,
            viewWidth = paperContainer.width.toFloat(),
            horizontalPadding = dp(24).toFloat(),
        )
        applyViewportToLayers()
    }

    private fun showPaperSizeMenu(anchor: View) {
        val page = workspace.selectedPage() ?: return
        val sizes = if (page.viewSettings.paperSize == PaperSize.PDF) listOf(PaperSize.PDF) else PageAppearanceCatalog.paperTemplates
        MojianCommandMenu.show(
            this,
            anchor,
            sizes.map { size ->
                MojianCommandMenu.Item(
                    size.menuLabel,
                    "document_page",
                    checked = size == page.viewSettings.paperSize,
                    action = {
                        if (size != PaperSize.PDF) {
                            updateCurrentPageView {
                                it.paperSize = size
                                it.customDocumentWidth = null
                                it.customDocumentHeight = null
                                it.a4PageCount = 1
                            }
                        }
                    },
                )
            },
            widthDp = 232,
        )
    }

    private fun showPageColorMenu(anchor: View) {
        val page = workspace.selectedPage() ?: return
        toolDetailPopup?.dismiss()
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
        lateinit var popup: PopupWindow
        for (color in PageAppearanceCatalog.pageColors) {
            row.addView(
                FrameLayout(this).apply {
                    contentDescription = color.description
                    setDarkTooltip(this, color.description)
                    addView(
                        View(this@MainActivity).apply {
                            background =
                                rounded(
                                    color.displayArgb(page.viewSettings.darkBackground),
                                    dp(2).toFloat(),
                                    if (color == page.viewSettings.pageColor) ACCENT_PURPLE else BORDER_STRONG,
                                )
                        },
                        FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER),
                    )
                    setOnClickListener {
                        updateCurrentPageView { it.pageColor = color }
                        popup.dismiss()
                    }
                },
                LinearLayout.LayoutParams(dp(40), dp(36)),
            )
        }
        popup =
            PopupWindow(row, dp(216), dp(48), true).apply {
                isOutsideTouchable = true
                setBackgroundDrawable(rounded(TOOLBAR_BACKGROUND, dp(3).toFloat(), BORDER_STRONG))
                elevation = dp(10).toFloat()
                setOnDismissListener { if (toolDetailPopup === this) toolDetailPopup = null }
            }
        popup.showAsDropDown(anchor, -dp(8), dp(2))
        toolDetailPopup = popup
    }

    private fun showAddToolMenu(anchor: View) {
        MojianCommandMenu.show(
            this,
            anchor,
            listOf(
                MojianCommandMenu.Item("添加钢笔", "pen", action = { addTool(DrawingToolKind.PEN) }),
                MojianCommandMenu.Item("添加荧光笔", "highlight", action = { addTool(DrawingToolKind.HIGHLIGHTER) }),
                MojianCommandMenu.Item("添加橡皮", "eraser", action = { addTool(DrawingToolKind.ERASER) }),
                MojianCommandMenu.Item("添加套索", "lasso", action = { addTool(DrawingToolKind.LASSO) }),
            ),
            widthDp = 220,
        )
    }

    private fun showNavigationAddMenu(anchor: View) {
        MojianCommandMenu.show(
            this,
            anchor,
            listOf(
                MojianCommandMenu.Item("新建笔记本", "notebook", action = {
                notebookLibrary.addNotebook()
                clearContentSelection()
                persistWorkspacePreferences()
                renderWorkspace()
                }),
                MojianCommandMenu.Item("新建分区", "add", action = {
                workspace.addSectionAtFocus()
                persistWorkspacePreferences()
                renderWorkspace()
                }),
            ),
            widthDp = 224,
        )
    }

    private fun addTool(kind: DrawingToolKind) {
        cancelStylusAction()
        val added = toolShelf.add(kind)
        if (kind == DrawingToolKind.PEN) lastPenToolId = added.id
        persistToolShelf()
        textInputLayer.abandonTemporaryEditors()
        updateSelectionInteraction()
        rebuildBrush()
        renderToolShelf()
        renderToolDetails()
    }

    private fun confirmClearPage() {
        val isPdfPage = workspace.selectedPage()?.pdfPage != null
        AlertDialog.Builder(this)
            .setTitle("清空当前页面？")
            .setMessage(
                if (isPdfPage) "将清空批注、文本、标记和后来插入的内容；PDF 页面保留。"
                else "将清空当前页面的笔迹、文本、标记和所有插入内容；嵌入文件也会从本页资产中删除，无法恢复。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                dryInkView.clear()
                textInputLayer.clearAll()
                tagLayer.clear()
                insertContentLayer.currentDocument()?.let { document ->
                    assetStore.clearPageAssetsExcept(
                        document.notebookId,
                        document.pageId,
                        insertContentLayer.retainedPageAssetNames(),
                    )
                }
                insertContentLayer.clearObjects(preservePageBackgrounds = true)
                saveBoundPageSession()
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
        val selected = !singleFingerPanEnabled && !insertSpaceModeEnabled && tool.id == effectiveDrawingTool().id
        return FrameLayout(this).apply {
            background =
                if (selected) rounded(SELECTED_LAVENDER, dp(2).toFloat(), BORDER_STRONG)
                else rounded(Color.TRANSPARENT, dp(2).toFloat(), Color.TRANSPARENT)
            val iconName =
                when (tool.kind) {
                    DrawingToolKind.PEN -> "pen"
                    DrawingToolKind.HIGHLIGHTER -> "highlight"
                    DrawingToolKind.ERASER -> "eraser"
                    DrawingToolKind.SHAPE -> "shapes"
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
            setDarkTooltip(this, "点击选择；再次点击打开参数")
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
            setDarkTooltip(this, label)
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
            setDarkTooltip(this, label)
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
            setDarkTooltip(this, label)
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
            setDarkTooltip(this, "${color.label}色")
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

    private fun launchDocumentPicker(
        requestCode: Int,
        mimeType: String,
        allowMultiple: Boolean = false,
        extraMimeTypes: Array<String>? = null,
        requireSelectedPage: Boolean = true,
    ) {
        if (requireSelectedPage && insertTarget() == null) {
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

    private fun performPasteCommand() {
        val imageUris = clipboardImageUris()
        if (imageUris.isNotEmpty()) {
            if (insertTarget() == null) {
                Toast.makeText(this, "请先选择页面", Toast.LENGTH_SHORT).show()
                return
            }
            textInputLayer.endEditing()
            chooseDisplayFilter(imageImportDisplayMode, "剪贴板图片如何显示？") { inverted ->
                importImages(imageUris, inverted)
            }
            return
        }
        if (textInputLayer.paste()) {
            Toast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show()
            return
        }
        if (insertObjectClipboard != null && insertContentLayer.currentDocument() != null) {
            pasteInsertObject()
            return
        }
        Toast.makeText(this, "剪贴板中没有可粘贴的图片或页面内容", Toast.LENGTH_SHORT).show()
    }

    private fun clipboardImageUris(): List<Uri> {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return emptyList()
        val descriptionHasImage =
            (0 until clip.description.mimeTypeCount).any { index ->
                ClipboardImagePolicy.isImageMimeType(clip.description.getMimeType(index))
            }
        return buildList {
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                val uri =
                    item.uri
                        ?: item.intent?.data
                        ?: item.text
                            ?.toString()
                            ?.takeIf { descriptionHasImage && (it.startsWith("content://") || it.startsWith("file://")) }
                            ?.let(Uri::parse)
                        ?: continue
                val mimeType = runCatching { contentResolver.getType(uri) }.getOrNull()
                if (ClipboardImagePolicy.isImageMimeType(mimeType) || descriptionHasImage) add(uri)
            }
        }.distinct()
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

    private fun importPdfPages(uri: Uri) {
        val target = pendingPdfPageTarget.also { pendingPdfPageTarget = null } ?: return
        val notebook = notebookLibrary.notebooks.firstOrNull { it.id == target.notebookId } ?: return
        Toast.makeText(this, "正在导入 PDF…", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            runCatching { pdfPageImporter.importPages(target.notebookId, uri) }
                .onSuccess { result ->
                    runOnUiThread {
                        if (!notebook.addPages(target.sectionId, result.pages)) {
                            result.pages.forEach { page -> pageContentRepository.deletePageData(target.notebookId, page.id) }
                            Toast.makeText(this, "PDF 导入位置已不存在", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        notebookLibrary.selectNotebook(target.notebookId)
                        val location = notebook.captureLocation()
                        target.splitSide?.takeIf { splitViewState.enabled }?.let { side ->
                            splitViewState.setLocation(side, location)
                        }
                        persistPageViewSettings()
                        persistWorkspacePreferences()
                        renderWorkspace()
                        Toast.makeText(this, "已导入 ${result.pages.size} 页 PDF", Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure { reportBackgroundFailure("PDF 导入失败", it) }
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

    private fun showInsertObjectContextMenu(item: InsertObject, viewX: Float, viewY: Float) {
        val layer = insertContentLayer
        val location = IntArray(2)
        layer.getLocationOnScreen(location)
        val items =
            if (item is InsertObject.Image) imageContextMenuItems(item)
            else objectContextMenuItems(item)
        MojianCommandMenu.showAt(
            this,
            layer,
            location[0] + viewX.roundToInt(),
            location[1] + viewY.roundToInt(),
            items,
            widthDp = 236,
            pointerButtonHandler = ::handleCanvasMenuPointerEvent,
        )
    }

    private fun imageContextMenuItems(image: InsertObject.Image): List<MojianCommandMenu.Item> {
        val orderMenu =
            listOf(
                MojianCommandMenu.Item("上移一层", "navigation", action = { insertContentLayer.moveSelectionForward() }),
                MojianCommandMenu.Item("置于顶层", "full_screen_maximize", action = { insertContentLayer.bringSelectionToFront() }),
                MojianCommandMenu.Item("下移一层", "navigation", dividerBefore = true, action = { insertContentLayer.moveSelectionBackward() }),
                MojianCommandMenu.Item("置于底层", "full_screen_minimize", action = { insertContentLayer.sendSelectionToBack() }),
            )
        val rotateMenu =
            listOf(
                MojianCommandMenu.Item("向右旋转 90°", "arrow_redo", action = { insertContentLayer.rotateSelectedImage(clockwise = true) }),
                MojianCommandMenu.Item("向左旋转 90°", "arrow_undo", action = { insertContentLayer.rotateSelectedImage(clockwise = false) }),
                MojianCommandMenu.Item("水平翻转", "split_view", dividerBefore = true, action = { insertContentLayer.flipSelectedImage(horizontal = true) }),
                MojianCommandMenu.Item("垂直翻转", "split_view", action = { insertContentLayer.flipSelectedImage(horizontal = false) }),
            )
        val page = workspace.selectedPage()
        val sectionId = workspace.selectedPageSectionId
        val selectedPageLink = page?.let { pageLink(workspace.id, sectionId, it.id) }
        return listOf(
            MojianCommandMenu.Item("剪切", "cut", action = { copySelectedInsertObject(cut = true) }),
            MojianCommandMenu.Item("复制", "copy", action = { copySelectedInsertObject(cut = false) }),
            MojianCommandMenu.Item("粘贴", "clipboard_paste", enabled = insertObjectClipboard != null, action = ::pasteInsertObject),
            MojianCommandMenu.Item("另存为…", "document_page", dividerBefore = true, action = { saveImageAs(image) }),
            MojianCommandMenu.Item("链接…", "link", enabled = false),
            MojianCommandMenu.Item(
                "复制指向段落的链接",
                "link",
                enabled = selectedPageLink != null,
                action = selectedPageLink?.let { link -> { copyNavigationLink("页面链接", link) } },
            ),
            MojianCommandMenu.Item("顺序", "navigation", dividerBefore = true, submenu = orderMenu),
            MojianCommandMenu.Item("旋转", "arrow_redo", submenu = rotateMenu),
            MojianCommandMenu.Item("移动", "hand_left", enabled = false, dividerBefore = true),
            MojianCommandMenu.Item("裁剪", "crop", enabled = false),
            MojianCommandMenu.Item("还原为原始尺寸", "full_screen_maximize", dividerBefore = true, action = { insertContentLayer.restoreSelectedImageSize() }),
            MojianCommandMenu.Item("将图片设置为背景", "image", action = { insertContentLayer.setSelectedImageAsBackground() }),
        )
    }

    private fun objectContextMenuItems(item: InsertObject): List<MojianCommandMenu.Item> =
        listOf(
            MojianCommandMenu.Item(if (item is InsertObject.Table) "编辑" else "打开", "document_page", action = { activateInsertObject(item) }),
            MojianCommandMenu.Item("复制", "copy", action = { insertContentLayer.duplicateSelection() }),
            MojianCommandMenu.Item(
                "顺序",
                "navigation",
                dividerBefore = true,
                submenu = listOf(
                    MojianCommandMenu.Item("上移一层", "navigation", action = { insertContentLayer.moveSelectionForward() }),
                    MojianCommandMenu.Item("置于顶层", "full_screen_maximize", action = { insertContentLayer.bringSelectionToFront() }),
                    MojianCommandMenu.Item("下移一层", "navigation", dividerBefore = true, action = { insertContentLayer.moveSelectionBackward() }),
                    MojianCommandMenu.Item("置于底层", "full_screen_minimize", action = { insertContentLayer.sendSelectionToBack() }),
                ),
            ),
            MojianCommandMenu.Item("删除", "delete_dismiss", dividerBefore = true, destructive = true, action = { insertContentLayer.deleteSelection() }),
        )

    private fun copySelectedInsertObject(cut: Boolean) {
        val document = insertContentLayer.currentDocument() ?: return
        val selected = insertContentLayer.selectedImage() ?: return
        insertObjectClipboard = InsertObjectClipboard(PageSessionKey(document.notebookId, document.pageId), JSONObject(selected.toJson().toString()))
        if (cut) insertContentLayer.deleteSelection()
        Toast.makeText(this, if (cut) "已剪切图片" else "已复制图片", Toast.LENGTH_SHORT).show()
    }

    private fun pasteInsertObject() {
        val clipboard = insertObjectClipboard ?: return
        val target = insertContentLayer.currentDocument() ?: return
        val json = JSONObject(clipboard.json.toString()).apply {
            put("id", "paste-${UUID.randomUUID()}")
            put("x", getDouble("x") + dp(18))
            put("y", getDouble("y") + dp(18))
            put("pageBackground", false)
        }
        val pasted = InsertObject.fromJson(json)
        if (clipboard.source.notebookId != target.notebookId || clipboard.source.pageId != target.pageId) {
            val sourceDirectory = pageContentRepository.assetDirectory(clipboard.source.notebookId, clipboard.source.pageId)
            val targetDirectory = pageContentRepository.assetDirectory(target.notebookId, target.pageId)
            val image = pasted as? InsertObject.Image
            listOfNotNull(image?.assetName, image?.sourceAssetName).distinct().forEach { assetName ->
                val source = File(sourceDirectory, PageContentRepository.safeId(assetName))
                val destination = File(targetDirectory, PageContentRepository.safeId(assetName))
                if (source.isFile && !destination.exists()) source.copyTo(destination)
            }
        }
        insertContentLayer.addObjectAndSelect(pasted)
        expandPageIfNeeded()
    }

    private fun saveImageAs(image: InsertObject.Image) {
        val document = insertContentLayer.currentDocument() ?: return
        val source = assetStore.assetFile(document.notebookId, document.pageId, image.assetName)
        if (!source.isFile) {
            Toast.makeText(this, "图片文件已丢失", Toast.LENGTH_SHORT).show()
            return
        }
        pendingImageSaveFile = source
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = image.mimeType
                putExtra(Intent.EXTRA_TITLE, image.displayName.ifBlank { source.name })
            },
            REQUEST_SAVE_IMAGE,
        )
    }

    private fun finishSaveImageAs(uri: Uri) {
        val source = pendingImageSaveFile ?: return
        pendingImageSaveFile = null
        ioExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: error("无法写入目标文件")
            }
            runOnUiThread {
                Toast.makeText(this, if (result.isSuccess) "图片已保存" else "图片保存失败", Toast.LENGTH_SHORT).show()
            }
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
        if (type != PendingExport.SECTION_PACKAGE && workspace.selectedPageId.isEmpty()) {
            Toast.makeText(this, "请先选择要导出的页面", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExport = type
        val pageTitle = workspace.selectedPage()?.title ?: "页面"
        val fileName =
            when (type) {
                PendingExport.PDF -> "${safeFileName(pageTitle)}.pdf"
                PendingExport.PRIVATE_PACKAGE -> "${safeFileName(workspace.title)}.mjn"
                PendingExport.SECTION_PACKAGE -> {
                    val target = pendingSectionExport
                    val notebook = notebookLibrary.notebooks.firstOrNull { it.id == target?.notebookId }
                    val section = target?.sectionId?.let { notebook?.section(it) }
                    "${safeFileName(section?.title ?: "分区")}.mjn"
                }
            }
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                this.type = if (type == PendingExport.PDF) "application/pdf" else "application/zip"
                putExtra(Intent.EXTRA_TITLE, fileName)
            },
            REQUEST_EXPORT,
        )
    }

    private fun startSectionExport(notebook: NotebookWorkspace, section: SectionNode) {
        pendingSectionExport = SectionExportTarget(notebook.id, section.id)
        startExport(PendingExport.SECTION_PACKAGE)
    }

    private fun exportToUri(uri: Uri, type: PendingExport) {
        val sectionExportTarget = pendingSectionExport
        val snapshot = captureExportLayers()
        if (type == PendingExport.PDF && snapshot == null) return
        val layers = snapshot?.layers ?: linkedMapOf()
        val pageId = workspace.selectedPageId
        val notebook = workspace
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show()
        ioExecutor.execute {
            runCatching {
                contentResolver.openOutputStream(uri, "w").use { output ->
                    requireNotNull(output) { "无法写入目标文件" }
                    val pngLayers = layers.entries.associate { (name, bitmap) -> name to bitmapToPng(bitmap) }
                    when (type) {
                        PendingExport.PDF -> {
                            val rasterLayers = layers.map { (name, bitmap) -> bitmapToPdfLayer(name, bitmap) }
                            LayeredPdfWriter.write(output, rasterLayers, requireNotNull(snapshot).pageSize)
                        }
                        PendingExport.PRIVATE_PACKAGE ->
                            NotebookPackageExporter(pageContentRepository).write(output, notebook, pageId, pngLayers)
                        PendingExport.SECTION_PACKAGE -> {
                            val target = requireNotNull(sectionExportTarget) { "分区导出目标已丢失" }
                            val targetNotebook = notebookLibrary.notebooks.first { it.id == target.notebookId }
                            val section = requireNotNull(targetNotebook.section(target.sectionId)) { "分区已不存在" }
                            val currentPageId =
                                pageId.takeIf { id ->
                                    notebook.id == targetNotebook.id && targetNotebook.pagesInSectionTree(section.id).any { it.id == id }
                                }
                            NotebookPackageExporter(pageContentRepository)
                                .writeSection(output, targetNotebook, section, currentPageId, pngLayers.takeIf { currentPageId != null }.orEmpty())
                        }
                    }
                }
            }.onSuccess { runOnUiThread { Toast.makeText(this, "导出完成", Toast.LENGTH_LONG).show() } }
                .onFailure { reportBackgroundFailure("导出失败", it) }
            layers.values.forEach(Bitmap::recycle)
        }
    }

    private fun captureExportLayers(): ExportSnapshot? {
        val page = workspace.selectedPage() ?: return null
        val pdfInfo = page.pdfPage
        val documentWidth = page.viewSettings.documentWidth
        val documentHeight = page.viewSettings.documentHeight
        val fixedDocumentExport = pdfInfo != null && documentWidth != null && documentHeight != null
        val contentCrop = if (page.viewSettings.paperSize == PaperSize.BOUNDLESS) BoundlessPageLayout.exportBounds(documentContentBounds()) else null
        val exportWidth = contentCrop?.let { it.right - it.left } ?: documentWidth
        val exportHeight = contentCrop?.let { it.bottom - it.top } ?: documentHeight
        val documentExport = fixedDocumentExport || contentCrop != null
        val (width, height) =
            if (documentExport) {
                val longest = maxOf(requireNotNull(exportWidth), requireNotNull(exportHeight)).coerceAtLeast(1f)
                val scale = PDF_EXPORT_LONGEST_SIDE_PX / longest
                (exportWidth * scale).roundToInt().coerceAtLeast(1) to
                    (exportHeight * scale).roundToInt().coerceAtLeast(1)
            } else {
                dryInkView.width to dryInkView.height
            }
        if (width <= 0 || height <= 0) {
            Toast.makeText(this, "页面尚未完成布局", Toast.LENGTH_SHORT).show()
            return null
        }
        val documentMatrix =
            if (documentExport) {
                Matrix().apply {
                    val scaleX = width / requireNotNull(exportWidth)
                    val scaleY = height / requireNotNull(exportHeight)
                    setValues(floatArrayOf(scaleX, 0f, -(contentCrop?.left ?: 0f) * scaleX, 0f, scaleY, -(contentCrop?.top ?: 0f) * scaleY, 0f, 0f, 1f))
                }
            } else {
                viewport.documentToViewMatrix()
            }
        val background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        dryInkView.renderBackgroundForExport(Canvas(background), documentMatrix)
        val inserted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        insertContentLayer.renderForExport(Canvas(inserted), documentMatrix)
        val inkAndText = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(inkAndText).also { canvas ->
            dryInkView.renderInkForExport(canvas, documentMatrix)
            tagLayer.renderForExport(canvas, documentMatrix)
            textInputLayer.renderForExport(canvas, documentMatrix)
        }
        ExportColorTransform.applyTo(inkAndText)
        return ExportSnapshot(
            layers = linkedMapOf("页面背景" to background, "插入内容" to inserted, "墨迹与文字" to inkAndText),
            pageSize = pdfInfo?.let { PdfOutputPageSize(it.widthPoints, it.heightPoints) }
                ?: contentCrop?.let { PdfOutputPageSize((it.right - it.left) / DOCUMENT_UNITS_PER_PDF_POINT, (it.bottom - it.top) / DOCUMENT_UNITS_PER_PDF_POINT) },
        )
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
            setDarkTooltip(this, description)
            gravity = Gravity.CENTER
            setCompoundDrawablesRelativeWithIntrinsicBounds(fluentIcon(icon), null, null, null)
            compoundDrawableTintList = ColorStateList.valueOf(if (description == "导航") Color.WHITE else TEXT_PRIMARY)
            background = rounded(Color.TRANSPARENT, 0f, Color.TRANSPARENT)
            setOnClickListener { action(this) }
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

    private fun setDarkTooltip(view: View, label: CharSequence) {
        view.setOnHoverListener { hovered, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    dismissDarkTooltip()
                    darkTooltipAnchor = hovered
                    darkTooltipLabel = label
                    mainHandler.postDelayed(showDarkTooltip, DARK_TOOLTIP_DELAY_MILLIS)
                }
                MotionEvent.ACTION_HOVER_EXIT -> if (darkTooltipAnchor === hovered) dismissDarkTooltip()
            }
            false
        }
    }

    private fun dismissDarkTooltip() {
        mainHandler.removeCallbacks(showDarkTooltip)
        darkTooltipPopup?.dismiss()
        darkTooltipPopup = null
        darkTooltipAnchor = null
        darkTooltipLabel = ""
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
        if (color == InkPalette.BLACK && darkCanvasActive) Color.rgb(239, 239, 239) else color.argb

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
        private const val MINIMUM_PANORAMA_SENSITIVITY = 0.25f
        private const val MAXIMUM_PANORAMA_SENSITIVITY = 3f
        private const val ERASER_RADIUS_STEPS = 159
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
        private const val KEY_PANORAMA_SENSITIVITY = "panorama-sensitivity"
        private const val KEY_ACTIVE_NOTEBOOK_ID = "active-notebook-id"
        private const val KEY_FOCUSED_SECTION_ID = "focused-section-id"
        private const val KEY_SELECTED_PAGE_ID = "selected-page-id"
        private const val KEY_PAGE_VIEW_SETTINGS = "page-view-settings"
        private const val KEY_BACKGROUND_FORMAT = "background-format"
        private const val KEY_ZOOM_LOCKED = "zoom-locked"
        private const val KEY_LAST_PAGE_TEMPLATE = "last-page-template"
        private const val KEY_IMAGE_IMPORT_DISPLAY_MODE = "image-import-display-mode"
        private const val KEY_PDF_PRINTOUT_DISPLAY_MODE = "pdf-printout-display-mode"
        private const val KEY_NOTEBOOK_LIBRARY = "notebook-library"
        private const val KEY_KEYBOARD_SHORTCUTS = "keyboard-shortcuts"
        private const val KEY_XIAOMI_PEN_SHORTCUTS = "xiaomi-pen-shortcuts"
        private const val REQUEST_ATTACHMENT = 4101
        private const val REQUEST_PDF_PRINTOUT = 4102
        private const val REQUEST_SPREADSHEET = 4103
        private const val REQUEST_IMAGES = 4104
        private const val REQUEST_STICKER_IMPORT = 4105
        private const val REQUEST_EXPORT = 4106
        private const val REQUEST_PDF_PAGES = 4107
        private const val REQUEST_SAVE_IMAGE = 4108
        private const val PDF_EXPORT_LONGEST_SIDE_PX = 1800f
        private const val SPLIT_FOCUS_PULSE_MILLIS = 500L
        private const val DARK_TOOLTIP_DELAY_MILLIS = 500L
        private const val NAVIGATION_DRAG_ARM_MILLIS = 180L
        private const val TOOLBAR_ID_SEPARATOR = "\u001F"
        private val ZOOM_PRESETS = listOf(50, 75, 100, 125, 150, 200, 400, 800)
        private val TITLE_PURPLE = Color.rgb(20, 20, 20)
        private val RIBBON_BACKGROUND = Color.rgb(17, 17, 17)
        private val ACCENT_PURPLE = Color.rgb(218, 178, 233)
        private val SELECTED_LAVENDER = Color.rgb(55, 64, 72)
        private val TOOLBAR_BACKGROUND = Color.rgb(35, 35, 35)
        private val DARK_TOOLTIP_BACKGROUND = Color.rgb(58, 58, 58)
        private val NAV_BACKGROUND = Color.rgb(15, 15, 15)
        private val PAGE_LIST_BACKGROUND = Color.rgb(42, 42, 42)
        private val PAPER_COLOR = Color.rgb(10, 10, 10)
        private val BORDER_COLOR = Color.rgb(61, 61, 61)
        private val BORDER_STRONG = Color.rgb(87, 87, 87)
        private val TEXT_PRIMARY = Color.rgb(244, 244, 244)
        private val TEXT_SECONDARY = Color.rgb(185, 185, 185)
    }
}
