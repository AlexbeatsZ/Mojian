using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Controls;

public enum CanvasRenderLayer { Background, Inserts, InkAndText, Composite }

public sealed class CanvasPointerOverlay : FrameworkElement
{
    internal NoteCanvas? Owner { get; set; }
    protected override void OnRender(DrawingContext context) => Owner?.DrawPointerFeedback(context);
}

public sealed class CanvasWetInkOverlay : FrameworkElement
{
    internal NoteCanvas? Owner { get; set; }
    protected override void OnRender(DrawingContext context) => Owner?.DrawWetInk(context);
}

public sealed class CanvasDocumentScene : FrameworkElement
{
    internal NoteCanvas? Owner { get; set; }
    protected override void OnRender(DrawingContext context) => Owner?.DrawScreenScene(context);
}

public sealed class PageObjectEventArgs(PageObject value) : EventArgs
{
    public PageObject Value { get; } = value;
}

public partial class NoteCanvas : UserControl
{
    private enum DirectGestureMode { None, Move, Resize, Marquee }
    private static readonly JsonSerializerOptions SnapshotJson = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    private const double UnitsPerMillimeter = 96d / 25.4d;

    [DllImport("user32.dll")]
    private static extern uint GetDoubleClickTime();

    private readonly ViewportState _viewport = new();
    private sealed record UndoEntry(string? Json = null, string? AddedStrokeId = null)
    {
        public static implicit operator UndoEntry(string json) => new(Json: json);
    }
    private readonly Stack<UndoEntry> _undo = new();
    private readonly Stack<string> _redo = new();
    private readonly List<Point> _lasso = new();
    private readonly List<StrokeRecord> _selectedStrokes = new();
    private readonly List<CanvasTag> _selectedTags = new();
    private readonly List<TextItem> _selectedTextItems = new();
    private readonly List<PageObject> _selectedObjects = new();
    private readonly Dictionary<string, BitmapSource> _bitmapCache = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, BitmapSource> _pdfBitmapCache = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<string> _pendingPdfRenders = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<string> _failedPdfRenders = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<int> _activeTouchDevices = new();
    private readonly DispatcherTimer _saveTimer;
    private readonly DispatcherTimer _pressureTimer;
    private readonly DispatcherTimer _directLongPressTimer;
    private readonly DispatcherTimer _rightLassoHoldTimer;
    private readonly DispatcherTimer _stylusContextMenuTimer;
    private static SelectionClipboardData? _selectionClipboard;
    private readonly Stopwatch _strokeClock = new();
    private readonly StrokeDrawingCache _strokeDrawings = new();
    private bool _drawingDryScene;
    internal int SceneRenderCount { get; private set; }

    private StrokeRecord? _activeStroke;
    private Point _shapeStart;
    private bool _explicitShape;
    private bool _finishingPointer;
    private readonly HoldToStraighten _straighten = new();
    private readonly DispatcherTimer _straightenTimer = new() { Interval = TimeSpan.FromMilliseconds(30) };
    public Func<ToolPreset>? ResolveShapeSettings { get; set; }
    private Rect? _selectionBounds;
    private Point _lastPointer;
    private Point _dragZoomAnchor;
    private Point _transformPoint;
    private Point _lassoOrigin;
    private bool _isPanning;
    private bool _isDragZooming;
    private bool _isLassoing;
    private bool _isTransforming;
    private bool _stylusSelectionTransformActive;
    private bool _stylusSelectionTransformMoved;
    private int _resizeHandle = -1;
    private bool _isInsertingSpace;
    private bool _insertSpaceUndoPushed;
    private double _insertStartY;
    private double _insertCurrentY;
    private Point? _eraserViewPoint;
    private Point? _hoverViewPoint;
    private bool _stylusActive;
    private bool _stylusInRange;
    private bool _stylusEraserActive;
    private bool _middleNavigationActive;
    private bool _middleNavigationUsesStylus;
    private bool _rightLassoArmed;
    private bool _rightLassoActive;
    private bool _rightLassoUsesStylus;
    private bool _rightLassoHasStylusContact;
    private Point _rightLassoStartView;
    private Point _rightLassoLastView;
    private long _rightLassoArmedAtTick;
    private Point _pendingStylusContextMenuPoint;
    private bool _stylusBarrelButtonDown;
    private bool _panoramaSpringLoaded;
    private TouchDevice? _directTouchDevice;
    private bool _touchInsertSpaceActive;
    private DirectGestureMode _directGestureMode;
    private bool _directGestureCommitted;
    private bool _directGestureFromTouch;
    private bool _directUndoPushed;
    private bool _directLongPressTriggered;
    private Point _directDownView;
    private Point _directLastDocument;
    private LassoShape _activeLassoShape = LassoShape.Freeform;
    private TextItem? _temporaryTextItem;
    private TextBox? _temporaryTextEditor;
    private bool _temporaryTextCommitted;
    private int _temporaryTextUndoDepth;
    private long _ignoreMouseUntil;
    private bool _pressureEraseArmed;
    private readonly PressureEraseTravelGuard _pressureTravel = new();
    private bool _pressureCandidate;
    private bool _temporaryPressureEraser;
    private ToolPreset? _pressureCandidateEraserTool;
    private ToolPreset? _activePressureEraserTool;
    private Point _pressureStartView;
    private DateTime _pressureStartedAt;
    private bool _suppressEditorChange;
    private int _pdfRenderGeneration;

    public static readonly DependencyProperty DocumentProperty = DependencyProperty.Register(
        nameof(Document), typeof(PageDocument), typeof(NoteCanvas), new PropertyMetadata(null, OnDocumentChanged));
    public static readonly DependencyProperty AppearanceProperty = DependencyProperty.Register(
        nameof(Appearance), typeof(PageAppearance), typeof(NoteCanvas), new FrameworkPropertyMetadata(new PageAppearance(), FrameworkPropertyMetadataOptions.AffectsRender, OnAppearanceChanged));
    public static readonly DependencyProperty PageTitleProperty = DependencyProperty.Register(
        nameof(PageTitle), typeof(string), typeof(NoteCanvas), new FrameworkPropertyMetadata(string.Empty, FrameworkPropertyMetadataOptions.AffectsRender));
    public static readonly DependencyProperty SelectedToolProperty = DependencyProperty.Register(
        nameof(SelectedTool), typeof(ToolPreset), typeof(NoteCanvas), new PropertyMetadata(null, OnSelectedToolChanged));
    public static readonly DependencyProperty SingleFingerPanEnabledProperty = DependencyProperty.Register(
        nameof(SingleFingerPanEnabled), typeof(bool), typeof(NoteCanvas), new PropertyMetadata(false, OnPointerModeChanged));
    public static readonly DependencyProperty InsertSpaceModeEnabledProperty = DependencyProperty.Register(
        nameof(InsertSpaceModeEnabled), typeof(bool), typeof(NoteCanvas), new PropertyMetadata(false));
    public static readonly DependencyProperty PendingTagKindProperty = DependencyProperty.Register(
        nameof(PendingTagKind), typeof(CanvasTagKind?), typeof(NoteCanvas), new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault));
    public static readonly DependencyProperty ZoomLockedProperty = DependencyProperty.Register(
        nameof(ZoomLocked), typeof(bool), typeof(NoteCanvas), new PropertyMetadata(false));
    public static readonly DependencyProperty PredictionEnabledProperty = DependencyProperty.Register(
        nameof(PredictionEnabled), typeof(bool), typeof(NoteCanvas), new PropertyMetadata(true));
    public static readonly DependencyProperty PanoramaSensitivityProperty = DependencyProperty.Register(
        nameof(PanoramaSensitivity), typeof(double), typeof(NoteCanvas), new PropertyMetadata(1d));

    public PageDocument? Document { get => (PageDocument?)GetValue(DocumentProperty); set => SetValue(DocumentProperty, value); }
    public PageAppearance Appearance { get => (PageAppearance)GetValue(AppearanceProperty); set => SetValue(AppearanceProperty, value); }
    public string PageTitle { get => (string)GetValue(PageTitleProperty); set => SetValue(PageTitleProperty, value); }
    public ToolPreset? SelectedTool { get => (ToolPreset?)GetValue(SelectedToolProperty); set => SetValue(SelectedToolProperty, value); }
    public bool SingleFingerPanEnabled { get => (bool)GetValue(SingleFingerPanEnabledProperty); set => SetValue(SingleFingerPanEnabledProperty, value); }
    public bool InsertSpaceModeEnabled { get => (bool)GetValue(InsertSpaceModeEnabledProperty); set => SetValue(InsertSpaceModeEnabledProperty, value); }
    public CanvasTagKind? PendingTagKind { get => (CanvasTagKind?)GetValue(PendingTagKindProperty); set => SetValue(PendingTagKindProperty, value); }
    public bool ZoomLocked { get => (bool)GetValue(ZoomLockedProperty); set => SetValue(ZoomLockedProperty, value); }
    public bool PredictionEnabled { get => (bool)GetValue(PredictionEnabledProperty); set => SetValue(PredictionEnabledProperty, value); }
    public double PanoramaSensitivity { get => (double)GetValue(PanoramaSensitivityProperty); set => SetValue(PanoramaSensitivityProperty, value); }
    public ViewportState Viewport => _viewport;
    public Func<string, string>? ResolveAssetPath { get; set; }
    public Func<string, string>? ImportAssetPath { get; set; }
    public Func<ToolPreset?>? ResolvePressureEraserTool { get; set; }
    public Func<SpringLoadedToolKind, ToolPreset?>? BeginSpringLoadedTool { get; set; }
    public Action<SpringLoadedToolKind>? EndSpringLoadedTool { get; set; }

    public event EventHandler? DocumentChanged;
    public event EventHandler? ViewportChanged;
    public event EventHandler? PageMetadataChanged;
    public event EventHandler<PageObjectEventArgs>? ObjectContextRequested;

    public NoteCanvas()
    {
        InitializeComponent();
        PointerOverlay.Owner = this;
        WetInkOverlay.Owner = this;
        SceneLayer.Owner = this;
        Focusable = true;
        _saveTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(350) };
        _saveTimer.Tick += (_, _) => { _saveTimer.Stop(); RaiseDocumentChanged(); };
        _pressureTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
        _pressureTimer.Tick += (_, _) => CheckPressureEraseActivation();
        _directLongPressTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(650) };
        _directLongPressTimer.Tick += (_, _) =>
        {
            _directLongPressTimer.Stop();
            if (_directGestureFromTouch && !_directGestureCommitted) ShowDirectContextMenu(_directDownView);
        };
        _straightenTimer.Tick += (_, _) => TryStraighten();
        _rightLassoHoldTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(1) };
        _rightLassoHoldTimer.Tick += (_, _) =>
        {
            _rightLassoHoldTimer.Stop();
            ActivateTimedStylusRightLasso();
        };
        _stylusContextMenuTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(80) };
        _stylusContextMenuTimer.Tick += (_, _) =>
        {
            _stylusContextMenuTimer.Stop();
            if (_rightLassoArmed || Document == null) return;
            ShowDirectContextMenu(_pendingStylusContextMenuPoint);
        };
        AddHandler(Stylus.StylusDownEvent, new StylusDownEventHandler(HandleStylusDown), handledEventsToo: true);
        AddHandler(Stylus.StylusMoveEvent, new StylusEventHandler(HandleStylusMove), handledEventsToo: true);
        AddHandler(Stylus.StylusUpEvent, new StylusEventHandler(HandleStylusUp), handledEventsToo: true);
        AddHandler(Stylus.StylusButtonDownEvent, new StylusButtonEventHandler(HandleStylusButtonDown), handledEventsToo: true);
        AddHandler(Stylus.StylusButtonUpEvent, new StylusButtonEventHandler(HandleStylusButtonUp), handledEventsToo: true);
        AddHandler(UIElement.PreviewTouchDownEvent, new EventHandler<TouchEventArgs>(ObservePreviewTouchDown), handledEventsToo: true);
        AddHandler(UIElement.PreviewTouchUpEvent, new EventHandler<TouchEventArgs>(ObservePreviewTouchUp), handledEventsToo: true);
    }

    private void ObservePreviewTouchDown(object? sender, TouchEventArgs e)
    {
        _activeTouchDevices.Add(e.TouchDevice.Id);
        if (_activeTouchDevices.Count < 2) return;
        CancelDirectPointerForPageNavigation();
        AbandonTemporaryText();
    }

    private void ObservePreviewTouchUp(object? sender, TouchEventArgs e)
    {
        _activeTouchDevices.Remove(e.TouchDevice.Id);
    }

    private static void OnDocumentChanged(DependencyObject source, DependencyPropertyChangedEventArgs args)
    {
        var canvas = (NoteCanvas)source;
        canvas.CommitViewport(args.OldValue as PageDocument);
        if (canvas._activeStroke != null && args.OldValue is PageDocument old) old.Strokes.Remove(canvas._activeStroke);
        canvas._undo.Clear();
        canvas._redo.Clear();
        canvas.ClearTransientState();
        canvas._bitmapCache.Clear();
        canvas._pdfBitmapCache.Clear();
        canvas._pendingPdfRenders.Clear();
        canvas._failedPdfRenders.Clear();
        canvas._pdfRenderGeneration++;
        if (args.NewValue is PageDocument doc) canvas._viewport.Restore(doc.Viewport);
        else canvas._viewport.Reset();
        canvas.RebuildTextEditors();
        canvas.InvalidateVisual();
    }

    private static void OnAppearanceChanged(DependencyObject source, DependencyPropertyChangedEventArgs args)
    {
        var canvas = (NoteCanvas)source;
        canvas.UpdateTextEditorAppearance();
        canvas.InvalidateVisual();
    }

    private static void OnPointerModeChanged(DependencyObject source, DependencyPropertyChangedEventArgs args)
    {
        var canvas = (NoteCanvas)source;
        if (canvas._activeStroke != null) canvas.CancelStylusToolActionForNavigation();
        canvas.CancelRightButtonLasso();
        canvas.UpdatePointerCursor();
        canvas.InvalidateVisual();
    }

    private static void OnSelectedToolChanged(DependencyObject source, DependencyPropertyChangedEventArgs args)
    {
        var canvas = (NoteCanvas)source;
        if (canvas._activeStroke != null) canvas.CancelStylusToolActionForNavigation();
        canvas.CancelRightButtonLasso();
        canvas.UpdatePointerCursor();
        canvas.InvalidateVisual();
    }

    public void Undo()
    {
        if (Document == null || _undo.Count == 0) return;
        _redo.Push(Snapshot(Document));
        RestoreSnapshot(_undo.Pop());
    }

    public void Redo()
    {
        if (Document == null || _redo.Count == 0) return;
        _undo.Push(Snapshot(Document));
        RestoreSnapshot(_redo.Pop());
    }

    public void ClearPage()
    {
        if (Document == null) return;
        PushUndo();
        Document.Strokes.Clear();
        Document.Tags.Clear();
        Document.TextItems.Clear();
        Document.Objects.RemoveAll(item => !item.IsPageBackground);
        RebuildTextEditors();
        ClearSelection();
        MarkChanged(immediate: true);
        InvalidateVisual();
    }

    public bool DeleteSelection()
    {
        if (Document == null || (_selectedStrokes.Count == 0 && _selectedTags.Count == 0 && _selectedTextItems.Count == 0 && _selectedObjects.Count == 0)) return false;
        PushUndo();
        foreach (var stroke in _selectedStrokes) Document.Strokes.Remove(stroke);
        foreach (var tag in _selectedTags) Document.Tags.Remove(tag);
        foreach (var text in _selectedTextItems) Document.TextItems.Remove(text);
        foreach (var item in _selectedObjects) Document.Objects.Remove(item);
        ClearSelection();
        RebuildTextEditors();
        MarkChanged(immediate: true);
        InvalidateVisual();
        return true;
    }

    public bool CopySelection(bool cut = false)
    {
        if (Document == null || !HasContentSelection()) return false;
        _selectionClipboard = new SelectionClipboardData(
            Document.NotebookId,
            Document.PageId,
            _selectedStrokes.Select(item => CloneStroke(item)).ToList(),
            _selectedTags.Select(item => CloneTag(item)).ToList(),
            _selectedTextItems.Select(item => CloneText(item)).ToList(),
            _selectedObjects.Select(item => new SelectionObjectClipboard(
                item.CloneWithNewId(offset: 0),
                string.IsNullOrWhiteSpace(item.AssetName) ? null : ResolveAssetPath?.Invoke(item.AssetName),
                string.IsNullOrWhiteSpace(item.SourceAssetName) ? null : ResolveAssetPath?.Invoke(item.SourceAssetName))).ToList());
        if (cut) DeleteSelection();
        return true;
    }

    public bool PasteSelection()
    {
        var clipboard = _selectionClipboard;
        if (Document == null || clipboard == null) return false;
        PushUndo();
        ClearSelection();
        const float offset = 24;

        foreach (var source in clipboard.Strokes)
        {
            var item = CloneStroke(source, offset);
            Document.Strokes.Add(item);
            _selectedStrokes.Add(item);
        }
        foreach (var source in clipboard.Tags)
        {
            var item = CloneTag(source, offset);
            Document.Tags.Add(item);
            _selectedTags.Add(item);
        }
        foreach (var source in clipboard.TextItems)
        {
            var item = CloneText(source, offset);
            Document.TextItems.Add(item);
            _selectedTextItems.Add(item);
        }
        foreach (var source in clipboard.Objects)
        {
            var item = source.Item.CloneWithNewId(offset);
            var samePage = clipboard.NotebookId == Document.NotebookId && clipboard.PageId == Document.PageId;
            if (!samePage && !string.IsNullOrWhiteSpace(source.AssetPath) && File.Exists(source.AssetPath))
                item.AssetName = ImportAssetPath?.Invoke(source.AssetPath) ?? item.AssetName;
            if (!samePage && !string.IsNullOrWhiteSpace(source.SourceAssetPath) && File.Exists(source.SourceAssetPath))
                item.SourceAssetName = ImportAssetPath?.Invoke(source.SourceAssetPath) ?? item.SourceAssetName;
            Document.Objects.Add(item);
            _selectedObjects.Add(item);
        }

        RebuildTextEditors();
        RecomputeSelectionBounds();
        MarkChanged(immediate: true);
        InvalidateVisual();
        return true;
    }

    public bool DuplicateSelection()
    {
        if (!CopySelection()) return false;
        return PasteSelection();
    }

    public bool ApplySelectionColor(InkPalette color)
    {
        if (Document == null || (_selectedStrokes.Count == 0 && _selectedTextItems.Count == 0)) return false;
        PushUndo();
        foreach (var stroke in _selectedStrokes) { stroke.Color = color; stroke.ColorHex = string.Empty; }
        foreach (var text in _selectedTextItems)
        {
            text.Color = color;
            text.ForegroundHex = string.Empty;
        }
        MarkChanged(immediate: true);
        InvalidateVisual();
        return true;
    }

    public bool MoveSelectedObjectsOneLayer(bool forward)
    {
        if (Document == null || _selectedObjects.Count == 0) return false;
        PushUndo();
        if (Document.Layers.Count > 0)
        {
            foreach (var item in _selectedObjects) PageScene.ReorderObject(Document, item, forward ? 1 : -1, false);
            MarkChanged(immediate: true); InvalidateVisual(); return true;
        }
        var selected = _selectedObjects.ToHashSet();
        if (forward)
        {
            for (var i = Document.Objects.Count - 2; i >= 0; i--)
            {
                if (!selected.Contains(Document.Objects[i]) || selected.Contains(Document.Objects[i + 1])) continue;
                (Document.Objects[i], Document.Objects[i + 1]) = (Document.Objects[i + 1], Document.Objects[i]);
            }
        }
        else
        {
            for (var i = 1; i < Document.Objects.Count; i++)
            {
                if (!selected.Contains(Document.Objects[i]) || selected.Contains(Document.Objects[i - 1])) continue;
                (Document.Objects[i], Document.Objects[i - 1]) = (Document.Objects[i - 1], Document.Objects[i]);
            }
        }
        MarkChanged(immediate: true);
        InvalidateVisual();
        return true;
    }

    public bool MoveSelectedObjectsToEdge(bool front)
    {
        if (Document == null || _selectedObjects.Count == 0) return false;
        PushUndo();
        if (Document.Layers.Count > 0)
        {
            foreach (var item in _selectedObjects) PageScene.ReorderObject(Document, item, front ? 1 : -1, true);
            MarkChanged(immediate: true); InvalidateVisual(); return true;
        }
        var selected = Document.Objects.Where(_selectedObjects.Contains).ToList();
        var unselected = Document.Objects.Where(item => !_selectedObjects.Contains(item)).ToList();
        Document.Objects.Clear();
        Document.Objects.AddRange(front ? unselected.Concat(selected) : selected.Concat(unselected));
        MarkChanged(immediate: true);
        InvalidateVisual();
        return true;
    }

    public bool SelectAllContent()
    {
        if (Document == null) return false;
        ClearSelection();
        _selectedStrokes.AddRange(Document.Strokes.Where(item => PageScene.IsVisible(Document, item.LayerId)));
        _selectedTags.AddRange(Document.Tags);
        _selectedTextItems.AddRange(Document.TextItems.Where(item => PageScene.IsVisible(Document, item.LayerId) && SelectionGeometry.HasVisibleText(item.Text)));
        _selectedObjects.AddRange(Document.Objects.Where(item => PageScene.IsVisible(Document, item.LayerId) && !item.IsPageBackground));
        RecomputeSelectionBounds();
        InvalidateVisual();
        return HasContentSelection();
    }

    public void AttachRightButtonLassoDismissal(ContextMenu menu)
    {
        menu.AddHandler(Stylus.StylusButtonDownEvent, new StylusButtonEventHandler((_, e) =>
        {
            if (!IsBarrelButton(e.StylusButton)) return;
            var point = ContextMenuPointToCanvas(menu, e.GetPosition(menu));
            menu.IsOpen = false;
            Focus();
            _stylusInRange = true;
            _stylusBarrelButtonDown = true;
            BeginRightButtonLasso(point, usesStylus: true, hasStylusContact: !e.StylusDevice.InAir);
            e.Handled = true;
        }), handledEventsToo: true);
        menu.AddHandler(Mouse.PreviewMouseDownEvent, new MouseButtonEventHandler((_, e) =>
        {
            var point = ContextMenuPointToCanvas(menu, e.GetPosition(menu));
            if (e.ChangedButton == MouseButton.Middle)
            {
                menu.IsOpen = false;
                Focus();
                if (_rightLassoArmed) CancelRightButtonLasso();
                BeginPanoramaSpringLoadedTool();
                _middleNavigationActive = true;
                _middleNavigationUsesStylus = IsPhysicalStylus(e.StylusDevice) || _stylusInRange;
                BeginPointer(
                    point,
                    pressure: 0.65f,
                    tiltX: 0,
                    tiltY: 0,
                    isStylus: _middleNavigationUsesStylus,
                    forcePan: true,
                    forceErase: false);
                e.Handled = true;
                return;
            }
            if (e.ChangedButton != MouseButton.Right) return;
            var usesStylus = IsPhysicalStylus(e.StylusDevice) ||
                             HasPressedBarrelButton(e.StylusDevice) ||
                             _stylusInRange;
            menu.IsOpen = false;
            Focus();
            if (usesStylus) _stylusInRange = true;
            BeginRightButtonLasso(
                point,
                usesStylus,
                hasStylusContact: usesStylus && e.StylusDevice?.InAir == false);
            e.Handled = true;
        }), handledEventsToo: true);
    }

    private Point ContextMenuPointToCanvas(ContextMenu menu, Point menuPoint)
    {
        try
        {
            return PointFromScreen(menu.PointToScreen(menuPoint));
        }
        catch (InvalidOperationException)
        {
            return _hoverViewPoint ?? _rightLassoLastView;
        }
    }

    public void AddObject(PageObject item)
    {
        if (Document == null) return;
        PushUndo();
        Document.Objects.Add(item);
        if (!item.IsPageBackground) SelectOnlyObject(item);
        MarkChanged(immediate: true);
        InvalidateVisual();
    }

    public bool RemoveObject(PageObject item)
    {
        if (Document == null || item.IsPageBackground || !Document.Objects.Contains(item)) return false;
        PushUndo();
        Document.Objects.Remove(item);
        ClearSelection();
        MarkChanged(immediate: true);
        InvalidateVisual();
        return true;
    }

    public bool MoveObjectToFront(PageObject item) => Document?.Layers.Count > 0 ? ReorderSourceObject(item, 1, true) : ReorderObject(item, Document?.Objects.Count - 1 ?? 0);
    public bool MoveObjectToBack(PageObject item)
    {
        if (Document == null) return false;
        if (Document.Layers.Count > 0) return ReorderSourceObject(item, -1, true);
        var firstMovable = Document.Objects.FindIndex(candidate => !candidate.IsPageBackground);
        return ReorderObject(item, Math.Max(0, firstMovable));
    }
    public bool MoveObjectForward(PageObject item) => Document?.Layers.Count > 0 ? ReorderSourceObject(item, 1, false) : ReorderObject(item, (Document?.Objects.IndexOf(item) ?? -1) + 1);
    public bool MoveObjectBackward(PageObject item) => Document?.Layers.Count > 0 ? ReorderSourceObject(item, -1, false) : ReorderObject(item, (Document?.Objects.IndexOf(item) ?? -1) - 1);

    private bool ReorderSourceObject(PageObject item, int direction, bool edge)
    {
        if (Document == null || item.IsPageBackground) return false;
        PushUndo();
        if (!PageScene.ReorderObject(Document, item, direction, edge)) return false;
        FinishObjectMutation(item); return true;
    }

    public bool RotateImageObject(PageObject item, bool clockwise)
    {
        if (!CanMutateImage(item)) return false;
        PushUndo();
        var centerX = item.X + item.Width / 2;
        var centerY = item.Y + item.Height / 2;
        (item.Width, item.Height) = (item.Height, item.Width);
        item.X = centerX - item.Width / 2;
        item.Y = centerY - item.Height / 2;
        item.RotationQuarterTurns = ((item.RotationQuarterTurns + (clockwise ? 1 : 3)) % 4 + 4) % 4;
        FinishObjectMutation(item);
        return true;
    }

    public bool FlipImageObject(PageObject item, bool horizontal)
    {
        if (!CanMutateImage(item)) return false;
        PushUndo();
        if (horizontal) item.FlipHorizontal = !item.FlipHorizontal;
        else item.FlipVertical = !item.FlipVertical;
        FinishObjectMutation(item);
        return true;
    }

    public bool RestoreImageObjectSize(PageObject item)
    {
        if (!CanMutateImage(item)) return false;
        var bitmap = LoadBitmap(item.AssetName, inverted: false);
        if (bitmap == null) return false;
        PushUndo();
        var centerX = item.X + item.Width / 2;
        var centerY = item.Y + item.Height / 2;
        item.Width = item.RotationQuarterTurns % 2 == 0 ? bitmap.PixelWidth : bitmap.PixelHeight;
        item.Height = item.RotationQuarterTurns % 2 == 0 ? bitmap.PixelHeight : bitmap.PixelWidth;
        item.X = centerX - item.Width / 2;
        item.Y = centerY - item.Height / 2;
        FinishObjectMutation(item);
        return true;
    }

    public bool SetImageObjectAsBackground(PageObject item)
    {
        if (!CanMutateImage(item) || Document == null) return false;
        PushUndo();
        item.IsPageBackground = true;
        Document.Objects.Remove(item);
        Document.Objects.Insert(0, item);
        ClearSelection();
        MarkChanged(immediate: true);
        InvalidateVisual();
        return true;
    }

    private bool ReorderObject(PageObject item, int targetIndex)
    {
        if (Document == null || item.IsPageBackground) return false;
        var current = Document.Objects.IndexOf(item);
        if (current < 0) return false;
        var firstMovable = Document.Objects.FindIndex(candidate => !candidate.IsPageBackground);
        firstMovable = Math.Max(0, firstMovable);
        targetIndex = Math.Clamp(targetIndex, firstMovable, Document.Objects.Count - 1);
        if (current == targetIndex) return false;
        PushUndo();
        Document.Objects.RemoveAt(current);
        Document.Objects.Insert(Math.Clamp(targetIndex, firstMovable, Document.Objects.Count), item);
        FinishObjectMutation(item);
        return true;
    }

    private bool CanMutateImage(PageObject item) =>
        Document != null && Document.Objects.Contains(item) && item.IsImageLike && !item.IsPageBackground;

    private void FinishObjectMutation(PageObject item)
    {
        SelectOnlyObject(item);
        MarkChanged(immediate: true);
        InvalidateVisual();
    }

    public void UpdateObject(PageObject item, Action<PageObject> update)
    {
        if (Document == null || !Document.Objects.Contains(item)) return;
        PushUndo();
        update(item);
        if (_selectedObjects.Contains(item)) RecomputeSelectionBounds();
        MarkChanged(immediate: true);
        InvalidateVisual();
    }

    public void UpdateTextItem(TextItem item, Action<TextItem> update)
    {
        if (Document == null || !Document.TextItems.Contains(item)) return;
        PushUndo();
        update(item);
        var editor = EditorLayer.Children.OfType<TextBox>().FirstOrDefault(candidate => ReferenceEquals(candidate.Tag, item));
        if (editor != null)
        {
            ApplyTextItemStyle(editor, item);
            PositionEditor(editor, item);
        }
        MarkChanged(immediate: true);
        InvalidateVisual();
    }

    public void SetZoom(float scale)
    {
        _viewport.SetScale(scale, (float)(ActualWidth / 2), (float)(ActualHeight / 2));
        ViewportMutated();
    }

    public void FitPageWidth()
    {
        var width = Math.Max(1, Appearance.Width);
        var scale = (float)Math.Clamp((ActualWidth - 64) / width, _viewport.MinScale, _viewport.MaxScale);
        _viewport.Reset();
        _viewport.SetScale(scale, 0, 0);
        _viewport.Pan(32, 40);
        ViewportMutated();
    }

    public void CenterOnDocumentPoint(double documentX, double documentY)
    {
        var view = _viewport.DocumentToView(documentX, documentY);
        _viewport.Pan((float)(ActualWidth / 2 - view.X), (float)(ActualHeight / 2 - view.Y));
        ViewportMutated();
    }

    public BitmapSource RenderLayerBitmap(CanvasRenderLayer layer, int maxPixelWidth = 1800)
    {
        if (Document == null) throw new InvalidOperationException("没有可导出的页面。");
        var extent = CalculateDocumentExtent();
        var scale = Math.Min(1d, maxPixelWidth / Math.Max(1, extent.Width));
        var pixelWidth = Math.Max(1, (int)Math.Ceiling(extent.Width * scale));
        var pixelHeight = Math.Max(1, (int)Math.Ceiling(extent.Height * scale));
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.PushTransform(new ScaleTransform(scale, scale));
            dc.PushTransform(new TranslateTransform(-extent.X, -extent.Y));
            if (layer is CanvasRenderLayer.Background or CanvasRenderLayer.Composite)
                dc.DrawRectangle(Brushes.White, null, extent);
            DrawDocument(dc, layer, lightExport: true, extent);
            dc.Pop();
            dc.Pop();
        }
        var bitmap = new RenderTargetBitmap(pixelWidth, pixelHeight, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        bitmap.Freeze();
        return bitmap;
    }

    public DrawingGroup RenderLayerDrawing(CanvasRenderLayer layer, Rect? documentExtent = null)
    {
        if (Document == null) throw new InvalidOperationException("没有可导出的页面。");
        var extent = documentExtent ?? CalculateDocumentExtent();
        var drawing = new DrawingGroup();
        using (var dc = drawing.Open())
        {
            if (layer is CanvasRenderLayer.Background or CanvasRenderLayer.Composite)
                dc.DrawRectangle(Brushes.White, null, extent);
            DrawDocument(dc, layer, lightExport: true, extent);
        }
        drawing.Freeze();
        return drawing;
    }

    public Rect ExportDocumentExtent => CalculateDocumentExtent();

    protected override void OnMouseWheel(MouseWheelEventArgs e)
    {
        base.OnMouseWheel(e);
        if (Document == null) return;
        var point = e.GetPosition(this);
        if (Keyboard.Modifiers.HasFlag(ModifierKeys.Control))
        {
            _viewport.ZoomAt((float)point.X, (float)point.Y, e.Delta > 0 ? 1.12f : 0.89f);
        }
        else if (Keyboard.Modifiers.HasFlag(ModifierKeys.Shift))
        {
            _viewport.Pan(e.Delta / 2f, 0);
        }
        else
        {
            _viewport.Pan(0, e.Delta / 2f);
        }
        ViewportMutated();
        e.Handled = true;
    }

    protected override void OnMouseDown(MouseButtonEventArgs e)
    {
        base.OnMouseDown(e);
        if (Document == null) return;
        var promotedByStylus = IsPhysicalStylus(e.StylusDevice);
        if (e.ChangedButton == MouseButton.Middle)
        {
            Focus();
            if (_rightLassoArmed) CancelRightButtonLasso();
            if (_stylusActive) CancelStylusToolActionForNavigation();
            BeginPanoramaSpringLoadedTool();
            _middleNavigationActive = true;
            _middleNavigationUsesStylus = promotedByStylus || _stylusInRange;
            BeginPointer(
                e.GetPosition(this),
                pressure: 0.65f,
                tiltX: 0,
                tiltY: 0,
                isStylus: _middleNavigationUsesStylus,
                forcePan: true,
                forceErase: false);
            e.Handled = true;
            return;
        }
        if (e.ChangedButton == MouseButton.Right)
        {
            if (e.OriginalSource is TextBox editor && editor.IsKeyboardFocusWithin) return;
            Focus();
            var rightPoint = e.GetPosition(this);
            var hoverTolerance = Math.Max(SystemParameters.MinimumHorizontalDragDistance, SystemParameters.MinimumVerticalDragDistance) * 2;
            var matchesStylusHover = _stylusInRange && _hoverViewPoint is { } hover && Distance(rightPoint, hover) <= hoverTolerance;
            var barrelButtonPressedInSnapshot = HasPressedBarrelButton(e.StylusDevice);
            if (CanvasPointerFeedback.IgnoreStalePromotedStylusRightDown(
                    promotedByStylus,
                    _rightLassoArmed,
                    _stylusBarrelButtonDown,
                    barrelButtonPressedInSnapshot))
            {
                e.Handled = true;
                return;
            }
            var usesStylus = CanvasPointerFeedback.RightButtonUsesStylus(promotedByStylus, _stylusBarrelButtonDown, matchesStylusHover);
            var hasStylusContact = usesStylus && e.StylusDevice?.InAir == false;
            if (_stylusActive) CancelStylusToolActionForNavigation();
            BeginRightButtonLasso(rightPoint, usesStylus, hasStylusContact);
            e.Handled = true;
            return;
        }
        if (CanvasPointerFeedback.SuppressMouseStream(_stylusActive, promotedByStylus, stylusDrivenMiddleNavigation: false) || Environment.TickCount64 < _ignoreMouseUntil) return;
        if (e.ChangedButton != MouseButton.Left) return;
        Focus();
        var point = e.GetPosition(this);
        var forcePan = Keyboard.IsKeyDown(Key.Space);
        if (forcePan || PendingTagKind.HasValue)
            BeginPointer(point, pressure: 0.65f, tiltX: 0, tiltY: 0, isStylus: false, forcePan, forceErase: false);
        else
            BeginDirectPointer(point, fromTouch: false);
        e.Handled = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (Document == null) return;
        var promotedByStylus = IsPhysicalStylus(e.StylusDevice);
        if (CanvasPointerFeedback.RefreshHoverFromPromotedMouse(promotedByStylus, _stylusActive))
        {
            _stylusInRange = true;
            _hoverViewPoint = e.GetPosition(this);
            UpdatePointerCursor();
            PointerOverlay.InvalidateVisual();
        }
        if (_rightLassoArmed)
        {
            ContinueRightButtonLasso(e.GetPosition(this));
            e.Handled = true;
            return;
        }
        if (CanvasPointerFeedback.PromotedHoverDrivesMiddleNavigation(
                promotedByStylus,
                _stylusActive,
                _middleNavigationActive,
                _middleNavigationUsesStylus))
        {
            ContinueMiddleNavigation(_hoverViewPoint ?? e.GetPosition(this), 0.65f, 0, 0);
            e.Handled = true;
            return;
        }
        if (CanvasPointerFeedback.SuppressMouseStream(
                _stylusActive,
                promotedByStylus,
                _middleNavigationActive && _middleNavigationUsesStylus))
        {
            if (_middleNavigationActive && _middleNavigationUsesStylus) e.Handled = true;
            return;
        }
        if (Environment.TickCount64 < _ignoreMouseUntil) return;
        _hoverViewPoint = e.GetPosition(this);
        UpdatePointerCursor();
        if (e.LeftButton == MouseButtonState.Pressed || e.MiddleButton == MouseButtonState.Pressed || IsMouseCaptured)
        {
            if (_directGestureMode != DirectGestureMode.None) ContinueDirectPointer(_hoverViewPoint.Value);
            else ContinuePointer(_hoverViewPoint.Value, 0.65f, 0, 0, isStylus: false);
            e.Handled = true;
        }
        if (e.LeftButton == MouseButtonState.Pressed || e.MiddleButton == MouseButtonState.Pressed || IsMouseCaptured)
            InvalidateVisual();
        else PointerOverlay.InvalidateVisual();
    }

    protected override void OnMouseEnter(MouseEventArgs e)
    {
        base.OnMouseEnter(e);
        if (IsPhysicalStylus(e.StylusDevice)) _stylusInRange = true;
        _hoverViewPoint = e.GetPosition(this);
        UpdatePointerCursor();
        PointerOverlay.InvalidateVisual();
    }

    protected override void OnMouseLeave(MouseEventArgs e)
    {
        base.OnMouseLeave(e);
        if (IsMouseCaptured || _stylusActive || _rightLassoArmed) return;
        _hoverViewPoint = null;
        UpdatePointerCursor();
        PointerOverlay.InvalidateVisual();
    }

    protected override void OnLostMouseCapture(MouseEventArgs e)
    {
        base.OnLostMouseCapture(e);
        if (!_finishingPointer && _explicitShape && _directTouchDevice == null) CancelStylusToolActionForNavigation();
        if (_rightLassoArmed && !_rightLassoUsesStylus) CancelRightButtonLasso();
    }

    protected override void OnLostStylusCapture(StylusEventArgs e)
    {
        base.OnLostStylusCapture(e);
        if (!_finishingPointer && _activeStroke != null) CancelStylusToolActionForNavigation();
        if (_rightLassoArmed && _rightLassoUsesStylus) CancelRightButtonLasso();
    }

    protected override void OnLostKeyboardFocus(KeyboardFocusChangedEventArgs e)
    {
        base.OnLostKeyboardFocus(e);
        if (_activeStroke != null) CancelStylusToolActionForNavigation();
        CancelRightButtonLasso();
    }

    protected override void OnMouseUp(MouseButtonEventArgs e)
    {
        base.OnMouseUp(e);
        if (Document == null) return;
        if (e.ChangedButton == MouseButton.Right && _rightLassoArmed)
        {
            EndRightButtonLasso();
            e.Handled = true;
            return;
        }
        if (e.ChangedButton == MouseButton.Middle && _middleNavigationActive)
        {
            EndPointer(isStylus: _middleNavigationUsesStylus);
            _middleNavigationActive = false;
            _middleNavigationUsesStylus = false;
            EndPanoramaSpringLoadedTool();
            e.Handled = true;
            return;
        }
        if (CanvasPointerFeedback.SuppressMouseStream(_stylusActive, IsPhysicalStylus(e.StylusDevice), stylusDrivenMiddleNavigation: false) || Environment.TickCount64 < _ignoreMouseUntil) return;
        if (_directGestureMode != DirectGestureMode.None) EndDirectPointer(e.GetPosition(this));
        else EndPointer(isStylus: false);
        e.Handled = true;
    }

    protected override void OnStylusEnter(StylusEventArgs e)
    {
        base.OnStylusEnter(e);
        if (e.StylusDevice.TabletDevice.Type == TabletDeviceType.Touch) return;
        _stylusInRange = true;
        _hoverViewPoint = e.GetPosition(this);
        UpdatePointerCursor();
        PointerOverlay.InvalidateVisual();
    }

    private void HandleStylusDown(object sender, StylusDownEventArgs e)
    {
        if (Document == null || e.StylusDevice.TabletDevice.Type == TabletDeviceType.Touch) return;
        _stylusInRange = true;
        var points = e.GetStylusPoints(this);
        var point = points.Count > 0 ? points[^1] : default;
        _hoverViewPoint = point.ToPoint();
        UpdatePointerCursor();
        var barrelButtonPressedInSnapshot = HasPressedBarrelButton(e.StylusDevice);
        var rightGestureIntent = CanvasPointerFeedback.RightButtonGestureIntent(
            _rightLassoArmed,
            _stylusBarrelButtonDown,
            barrelButtonPressedInSnapshot);
        if (rightGestureIntent)
            BeginRightButtonLasso(point.ToPoint(), usesStylus: true, hasStylusContact: true);
        if (!CanvasPointerFeedback.StylusDownStartsDrawing(e.InAir, rightGestureIntent))
        {
            _stylusActive = false;
            _stylusEraserActive = false;
            if (rightGestureIntent) e.Handled = true;
            InvalidateVisual();
            return;
        }
        _stylusActive = true;
        _stylusEraserActive = e.StylusDevice.Inverted;
        _ignoreMouseUntil = Environment.TickCount64 + 250;
        if (_middleNavigationActive && _middleNavigationUsesStylus)
        {
            e.Handled = true;
            return;
        }
        BeginPointer(point.ToPoint(), point.PressureFactor, Tilt(point, StylusPointProperties.XTiltOrientation), Tilt(point, StylusPointProperties.YTiltOrientation), isStylus: true, forcePan: false, forceErase: _stylusEraserActive);
        e.Handled = true;
    }

    private void HandleStylusMove(object sender, StylusEventArgs e)
    {
        if (Document == null || e.StylusDevice.TabletDevice.Type == TabletDeviceType.Touch) return;
        _stylusInRange = true;
        var points = e.GetStylusPoints(this);
        if (points.Count > 0)
        {
            _hoverViewPoint = points[^1].ToPoint();
            UpdatePointerCursor();
        }
        if (_rightLassoArmed && _rightLassoUsesStylus)
        {
            if (points.Count > 0) ContinueRightButtonLasso(points[^1].ToPoint());
            e.Handled = true;
            return;
        }
        if (CanvasPointerFeedback.ContactStylusDrivesMiddleNavigation(
                _stylusActive,
                _middleNavigationActive,
                _middleNavigationUsesStylus) && points.Count > 0)
        {
            var latest = points[^1];
            ContinueMiddleNavigation(latest.ToPoint(), latest.PressureFactor, Tilt(latest, StylusPointProperties.XTiltOrientation), Tilt(latest, StylusPointProperties.YTiltOrientation));
            e.Handled = true;
            return;
        }
        if (_middleNavigationActive && _middleNavigationUsesStylus)
        {
            // In-air navigation is driven by the promoted mouse coordinates on
            // this Windows Ink device; using both streams would apply it twice.
            e.Handled = true;
            return;
        }
        if (!_stylusActive)
        {
            PointerOverlay.InvalidateVisual();
            return;
        }
        if (CanvasPointerFeedback.UseLatestStylusSample(_isPanning, _isDragZooming, stylusDrivenMiddleNavigation: false) && points.Count > 0)
        {
            var latest = points[^1];
            ContinuePointer(latest.ToPoint(), latest.PressureFactor, Tilt(latest, StylusPointProperties.XTiltOrientation), Tilt(latest, StylusPointProperties.YTiltOrientation), isStylus: true);
        }
        else
        {
            foreach (var point in points)
                ContinuePointer(point.ToPoint(), point.PressureFactor, Tilt(point, StylusPointProperties.XTiltOrientation), Tilt(point, StylusPointProperties.YTiltOrientation), isStylus: true);
        }
        e.Handled = true;
    }

    private void HandleStylusButtonDown(object sender, StylusButtonEventArgs e)
    {
        if (!IsBarrelButton(e.StylusButton)) return;
        _stylusBarrelButtonDown = true;
        if (Document == null || e.StylusDevice.TabletDevice.Type == TabletDeviceType.Touch) return;
        Focus();
        if (_stylusActive) CancelStylusToolActionForNavigation();
        BeginRightButtonLasso(
            e.GetPosition(this),
            usesStylus: true,
            hasStylusContact: !e.StylusDevice.InAir);
        e.Handled = true;
    }

    private void HandleStylusButtonUp(object sender, StylusButtonEventArgs e)
    {
        if (!IsBarrelButton(e.StylusButton)) return;
        _stylusBarrelButtonDown = false;
        // Wacom starts this gesture through a promoted MouseRightDown, but ends it
        // through the raw barrel-button-up event and never sends MouseRightUp here.
        // Once classified as a stylus gesture, the raw release is authoritative.
        if (_rightLassoArmed && _rightLassoUsesStylus)
        {
            EndRightButtonLasso();
            e.Handled = true;
        }
    }

    protected override void OnStylusLeave(StylusEventArgs e)
    {
        base.OnStylusLeave(e);
        if (_stylusActive) return;
        if (_middleNavigationActive && _middleNavigationUsesStylus)
        {
            EndPointer(isStylus: true);
            _middleNavigationActive = false;
            _middleNavigationUsesStylus = false;
            EndPanoramaSpringLoadedTool();
        }
        _stylusInRange = false;
        _hoverViewPoint = null;
        UpdatePointerCursor();
        PointerOverlay.InvalidateVisual();
    }

    private void HandleStylusUp(object sender, StylusEventArgs e)
    {
        var barrelButtonPressedInSnapshot = HasPressedBarrelButton(e.StylusDevice);
        if (CanvasPointerFeedback.StylusUpEndsRightButtonGesture(
                _rightLassoArmed,
                _rightLassoUsesStylus,
                barrelButtonPressedInSnapshot))
        {
            EndRightButtonLasso();
            _ignoreMouseUntil = Environment.TickCount64 + 250;
            e.Handled = true;
            return;
        }
        if (!_stylusActive) return;
        if (_rightLassoArmed && _rightLassoUsesStylus)
        {
            _stylusActive = false;
            _stylusEraserActive = false;
            _ignoreMouseUntil = Environment.TickCount64 + 250;
            UpdatePointerCursor();
            InvalidateVisual();
            e.Handled = true;
            return;
        }
        if (_middleNavigationActive && _middleNavigationUsesStylus)
        {
            _stylusActive = false;
            _stylusEraserActive = false;
            _ignoreMouseUntil = Environment.TickCount64 + 250;
            UpdatePointerCursor();
            InvalidateVisual();
            e.Handled = true;
            return;
        }
        EndPointer(isStylus: true);
        _stylusActive = false;
        _stylusEraserActive = false;
        _ignoreMouseUntil = Environment.TickCount64 + 250;
        UpdatePointerCursor();
        InvalidateVisual();
        e.Handled = true;
    }

    protected override void OnManipulationStarting(ManipulationStartingEventArgs e)
    {
        base.OnManipulationStarting(e);
        e.ManipulationContainer = this;
        e.Mode = ManipulationModes.Translate | ManipulationModes.Scale;
        e.Handled = true;
    }

    protected override void OnTouchDown(TouchEventArgs e)
    {
        _activeTouchDevices.Add(e.TouchDevice.Id);
        base.OnTouchDown(e);
        if (_activeTouchDevices.Count >= 2)
        {
            CancelDirectPointerForPageNavigation();
            AbandonTemporaryText();
            return;
        }
        if (_activeTouchDevices.Count != 1) return;
        var touchPoint = e.GetTouchPoint(this).Position;
        if (InsertSpaceModeEnabled && DirectInputPolicy.CanDragInsertSpace(DirectInputDevice.Finger))
        {
            BeginInsertSpace(touchPoint);
            _touchInsertSpaceActive = true;
        }
        else
        {
            if (e.OriginalSource is TextBox editor && editor.IsKeyboardFocusWithin) return;
            BeginDirectPointer(touchPoint, fromTouch: true);
        }
        _directTouchDevice = e.TouchDevice;
        CaptureTouch(e.TouchDevice);
        e.Handled = true;
    }

    protected override void OnTouchMove(TouchEventArgs e)
    {
        base.OnTouchMove(e);
        if (_directTouchDevice?.Id != e.TouchDevice.Id || _activeTouchDevices.Count != 1) return;
        var point = e.GetTouchPoint(this).Position;
        if (_touchInsertSpaceActive) ContinuePointer(point, 0.65f, 0, 0, isStylus: false);
        else if (_explicitShape) ContinuePointer(point, .5f, 0, 0, isStylus: false);
        else ContinueDirectPointer(point);
        e.Handled = true;
    }

    protected override void OnTouchUp(TouchEventArgs e)
    {
        var finishingDirect = _directTouchDevice?.Id == e.TouchDevice.Id;
        _activeTouchDevices.Remove(e.TouchDevice.Id);
        if (finishingDirect)
        {
            if (_touchInsertSpaceActive) EndPointer(isStylus: false);
            else if (_explicitShape) EndPointer(isStylus: false);
            else EndDirectPointer(e.GetTouchPoint(this).Position);
            _touchInsertSpaceActive = false;
            _directTouchDevice = null;
            ReleaseTouchCapture(e.TouchDevice);
            e.Handled = true;
        }
        base.OnTouchUp(e);
    }

    protected override void OnTouchLeave(TouchEventArgs e)
    {
        if (_directTouchDevice?.Id == e.TouchDevice.Id && e.TouchDevice.Captured == null)
        {
            _directTouchDevice = null;
            CancelDirectPointer();
            InvalidateVisual();
        }
        if (e.TouchDevice.Captured == null) _activeTouchDevices.Remove(e.TouchDevice.Id);
        base.OnTouchLeave(e);
    }

    protected override void OnManipulationDelta(ManipulationDeltaEventArgs e)
    {
        base.OnManipulationDelta(e);
        if (Document == null || _activeStroke != null) return;
        if (_activeTouchDevices.Count < 2)
        {
            e.Handled = true;
            return;
        }
        var origin = e.ManipulationOrigin;
        var scale = ZoomLocked ? 1f : (float)e.DeltaManipulation.Scale.X;
        _viewport.ZoomAt((float)origin.X, (float)origin.Y, scale);
        _viewport.Pan((float)e.DeltaManipulation.Translation.X, (float)e.DeltaManipulation.Translation.Y);
        UpdateTextEditorPositions();
        InvalidateVisual();
        e.Handled = true;
    }

    protected override void OnManipulationCompleted(ManipulationCompletedEventArgs e)
    {
        base.OnManipulationCompleted(e);
        ViewportMutated();
        _activeTouchDevices.Clear();
        e.Handled = true;
    }

    private void BeginDirectPointer(Point viewPoint, bool fromTouch)
    {
        if (Document == null) return;
        if (SelectedTool?.Kind == DrawingToolKind.Shape && !SingleFingerPanEnabled && !InsertSpaceModeEnabled) { BeginPointer(viewPoint, .5f, 0, 0, isStylus: false, forcePan: false, forceErase: false); return; }
        var editingEditor = EditorAt(viewPoint);
        var editingHit = editingEditor is { IsKeyboardFocusWithin: true, IsHitTestVisible: true };
        var selectedHit = HitSelectedContent(viewPoint);
        var handleHit = false;
        var handle = -1;
        if (_selectionBounds is { } bounds)
            handleHit = HitSelectionHandle(viewPoint, _viewport.DocumentToView(bounds), out handle);
        var intent = DirectInputPolicy.PointerDownIntent(1, true, editingHit, selectedHit, handleHit);
        if (intent == DirectPointerDownIntent.NativeTextEdit) return;

        _directGestureMode = intent == DirectPointerDownIntent.TransformSelection
            ? handleHit ? DirectGestureMode.Resize : DirectGestureMode.Move
            : DirectGestureMode.Marquee;
        _resizeHandle = handleHit ? handle : -1;
        _directGestureCommitted = false;
        _directGestureFromTouch = fromTouch;
        _directUndoPushed = false;
        _directLongPressTriggered = false;
        _directDownView = viewPoint;
        _directLastDocument = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        _transformPoint = _directLastDocument;
        if (fromTouch) _directLongPressTimer.Start();
        else CaptureMouse();
    }

    private void ContinueDirectPointer(Point viewPoint)
    {
        if (_directGestureMode == DirectGestureMode.None || Document == null) return;
        var distance = Distance(viewPoint, _directDownView);
        if (!_directGestureCommitted)
        {
            var threshold = Math.Max(SystemParameters.MinimumHorizontalDragDistance, SystemParameters.MinimumVerticalDragDistance);
            if (distance < threshold) return;
            _directLongPressTimer.Stop();
            _directGestureCommitted = true;
            if (_temporaryTextItem != null && !_temporaryTextCommitted)
            {
                AbandonTemporaryText();
                _directGestureMode = DirectGestureMode.Marquee;
            }
            if (_directGestureMode is DirectGestureMode.Move or DirectGestureMode.Resize)
            {
                PushUndo();
                _directUndoPushed = true;
                _isTransforming = true;
            }
            else
            {
                BeginLasso(_viewport.ViewToDocument(_directDownView.X, _directDownView.Y), LassoShape.Rectangle);
            }
        }

        var documentPoint = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        switch (_directGestureMode)
        {
            case DirectGestureMode.Move:
                TranslateSelection(documentPoint.X - _directLastDocument.X, documentPoint.Y - _directLastDocument.Y);
                _directLastDocument = documentPoint;
                break;
            case DirectGestureMode.Resize:
                ResizeSelection(documentPoint);
                _directLastDocument = documentPoint;
                break;
            case DirectGestureMode.Marquee:
                ExtendLasso(documentPoint);
                break;
        }
        InvalidateVisual();
    }

    private void EndDirectPointer(Point viewPoint)
    {
        _directLongPressTimer.Stop();
        if (IsMouseCaptured) ReleaseMouseCapture();
        if (_directLongPressTriggered)
        {
            ResetDirectPointer();
            return;
        }
        if (!_directGestureCommitted)
        {
            HandleDirectTap(viewPoint);
            ResetDirectPointer();
            return;
        }
        if (_directGestureMode == DirectGestureMode.Marquee)
        {
            _isLassoing = false;
            ComputeLassoSelection();
        }
        else
        {
            _isTransforming = false;
            RecomputeSelectionBounds();
            MarkChanged(immediate: true);
        }
        ResetDirectPointer();
    }

    private void CancelDirectPointerForPageNavigation()
    {
        if (_explicitShape) CancelStylusToolActionForNavigation();
        if (_directTouchDevice != null) ReleaseTouchCapture(_directTouchDevice);
        _directTouchDevice = null;
        CancelDirectPointer();
    }

    private void CancelDirectPointer()
    {
        _directLongPressTimer.Stop();
        if (_isInsertingSpace) CancelInsertSpace();
        if (_directGestureMode == DirectGestureMode.Marquee)
        {
            _isLassoing = false;
            _lasso.Clear();
        }
        else if (_directGestureCommitted && _directUndoPushed && _undo.Count > 0)
        {
            var snapshot = _undo.Pop();
            RestoreSnapshot(snapshot);
        }
        ResetDirectPointer();
    }

    private void ResetDirectPointer()
    {
        _directGestureMode = DirectGestureMode.None;
        _directGestureCommitted = false;
        _directGestureFromTouch = false;
        _directUndoPushed = false;
        _directLongPressTriggered = false;
        _isTransforming = false;
        _resizeHandle = -1;
        InvalidateVisual();
    }

    private void CancelInsertSpace()
    {
        _isInsertingSpace = false;
        _touchInsertSpaceActive = false;
        if (_insertSpaceUndoPushed && _undo.Count > 0) _undo.Pop();
        _insertSpaceUndoPushed = false;
        InvalidateVisual();
    }

    private void HandleDirectTap(Point viewPoint)
    {
        if (Document == null) return;
        var text = HitText(viewPoint);
        if (text != null)
        {
            if (_selectedTextItems.Count == 1 && ReferenceEquals(_selectedTextItems[0], text)) ActivateTextEditor(text);
            else { AbandonTemporaryText(); Focus(); SelectOnlyText(text); }
            return;
        }
        var item = HitObject(viewPoint);
        if (item is { IsPageBackground: false })
        {
            AbandonTemporaryText();
            Focus();
            if (!_selectedObjects.Contains(item)) SelectOnlyObject(item);
            return;
        }
        var tag = HitTag(viewPoint);
        if (tag != null)
        {
            AbandonTemporaryText();
            Focus();
            if (!_selectedTags.Contains(tag)) SelectOnlyTag(tag);
            return;
        }
        var stroke = HitStroke(viewPoint);
        if (stroke != null)
        {
            AbandonTemporaryText();
            Focus();
            if (!_selectedStrokes.Contains(stroke)) SelectOnlyStroke(stroke);
            return;
        }
        AbandonTemporaryText();
        Focus();
        ClearSelection();
        AddTemporaryTextItem(_viewport.ViewToDocument(viewPoint.X, viewPoint.Y));
    }

    private bool ShowDirectContextMenu(Point viewPoint)
    {
        if (Document == null) return false;
        var documentPoint = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        var hasSelection =
            _selectedStrokes.Count > 0 ||
            _selectedTags.Count > 0 ||
            _selectedTextItems.Count > 0 ||
            _selectedObjects.Count > 0;
        if (hasSelection && _selectionBounds is { } selectionBounds && selectionBounds.Contains(documentPoint))
        {
            return OpenCurrentSelectionContextMenu(viewPoint);
        }
        var item = HitObject(viewPoint);
        if (item is { IsPageBackground: false })
        {
            AbandonTemporaryText();
            Focus();
            SelectOnlyObject(item);
            ObjectContextRequested?.Invoke(this, new PageObjectEventArgs(item));
            _directLongPressTriggered = true;
            return true;
        }
        var text = HitText(viewPoint);
        var tag = HitTag(viewPoint);
        var stroke = HitStroke(viewPoint);
        if (text == null && tag == null && stroke == null)
        {
            AbandonTemporaryText();
            Focus();
            ClearSelection();
            return OpenCanvasContextMenu(viewPoint);
        }
        AbandonTemporaryText();
        Focus();
        if (text != null) SelectOnlyText(text);
        else if (tag != null) SelectOnlyTag(tag);
        else if (stroke != null) SelectOnlyStroke(stroke);
        return OpenSelectionContextMenu(viewPoint);
    }

    private bool OpenCurrentSelectionContextMenu(Point viewPoint)
    {
        if (_selectedObjects.Count == 1 &&
            _selectedStrokes.Count == 0 &&
            _selectedTags.Count == 0 &&
            _selectedTextItems.Count == 0)
        {
            ObjectContextRequested?.Invoke(this, new PageObjectEventArgs(_selectedObjects[0]));
            _directLongPressTriggered = true;
            return true;
        }
        return OpenSelectionContextMenu(viewPoint);
    }

    private bool OpenCanvasContextMenu(Point viewPoint)
    {
        if (Document == null) return false;
        var menu = CreateContextMenu(viewPoint);
        var paste = new MenuItem { Header = "粘贴", IsEnabled = _selectionClipboard != null };
        paste.Click += (_, _) => PasteSelection();
        menu.Items.Add(paste);
        var selectAll = new MenuItem
        {
            Header = "全选",
            IsEnabled = Document.Strokes.Count > 0 || Document.Tags.Count > 0 ||
                        Document.TextItems.Any(item => SelectionGeometry.HasVisibleText(item.Text)) ||
                        Document.Objects.Any(item => !item.IsPageBackground),
        };
        selectAll.Click += (_, _) => SelectAllContent();
        menu.Items.Add(selectAll);
        menu.IsOpen = true;
        _directLongPressTriggered = true;
        return true;
    }

    private bool OpenSelectionContextMenu(Point viewPoint)
    {
        var menu = CreateContextMenu(viewPoint);
        var cut = new MenuItem { Header = "剪切" };
        cut.Click += (_, _) => CopySelection(cut: true);
        menu.Items.Add(cut);
        var copy = new MenuItem { Header = "复制" };
        copy.Click += (_, _) => CopySelection();
        menu.Items.Add(copy);
        var paste = new MenuItem { Header = "粘贴", IsEnabled = _selectionClipboard != null };
        paste.Click += (_, _) => PasteSelection();
        menu.Items.Add(paste);
        var duplicate = new MenuItem { Header = "副本" };
        duplicate.Click += (_, _) => DuplicateSelection();
        menu.Items.Add(duplicate);

        if (_selectedStrokes.Count > 0 || _selectedTextItems.Count > 0)
        {
            menu.Items.Add(new Separator());
            var colors = new MenuItem { Header = "修改颜色" };
            foreach (var color in Enum.GetValues<InkPalette>())
            {
                var captured = color;
                var item = new MenuItem { Header = captured.GetLabel() };
                item.Click += (_, _) => ApplySelectionColor(captured);
                colors.Items.Add(item);
            }
            menu.Items.Add(colors);
        }
        if (_selectedObjects.Count > 0)
        {
            var layers = new MenuItem { Header = "顺序" };
            var forward = new MenuItem { Header = "上移一层" };
            forward.Click += (_, _) => MoveSelectedObjectsOneLayer(forward: true);
            layers.Items.Add(forward);
            var front = new MenuItem { Header = "置于顶层" };
            front.Click += (_, _) => MoveSelectedObjectsToEdge(front: true);
            layers.Items.Add(front);
            var backward = new MenuItem { Header = "下移一层" };
            backward.Click += (_, _) => MoveSelectedObjectsOneLayer(forward: false);
            layers.Items.Add(backward);
            var back = new MenuItem { Header = "置于底层" };
            back.Click += (_, _) => MoveSelectedObjectsToEdge(front: false);
            layers.Items.Add(back);
            menu.Items.Add(layers);
        }

        menu.Items.Add(new Separator());
        var selectAll = new MenuItem { Header = "全选" };
        selectAll.Click += (_, _) => SelectAllContent();
        menu.Items.Add(selectAll);
        var delete = new MenuItem { Header = "删除" };
        delete.Click += (_, _) => DeleteSelection();
        menu.Items.Add(delete);
        menu.IsOpen = true;
        _directLongPressTriggered = true;
        return true;
    }

    private ContextMenu CreateContextMenu(Point viewPoint)
    {
        var menu = new ContextMenu
        {
            Placement = PlacementMode.Relative,
            PlacementTarget = this,
            HorizontalOffset = viewPoint.X,
            VerticalOffset = viewPoint.Y,
        };
        AttachRightButtonLassoDismissal(menu);
        if (Document?.Layers.Count > 1)
        {
            var layers = new MenuItem { Header = "图层" };
            foreach (var layer in Document.Layers.OrderByDescending(item => item.Order))
            {
                var toggle = new MenuItem { Header = layer.Name, IsCheckable = true, IsChecked = layer.Visible };
                toggle.Click += (_, _) =>
                {
                    PushUndo(); layer.Visible = toggle.IsChecked; ClearSelection();
                    MarkChanged(immediate: true); RebuildTextEditors(); InvalidateVisual();
                };
                layers.Items.Add(toggle);
            }
            menu.Items.Add(layers);
            menu.Items.Add(new Separator());
        }
        return menu;
    }

    private void BeginPointer(Point viewPoint, float pressure, float tiltX, float tiltY, bool isStylus, bool forcePan, bool forceErase)
    {
        if (Document == null) return;
        _lastPointer = viewPoint;
        var docPoint = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);

        if (forcePan || (isStylus && SingleFingerPanEnabled))
        {
            _isDragZooming = CanvasPointerFeedback.NavigationUsesRadialZoom(
                Keyboard.Modifiers.HasFlag(ModifierKeys.Control),
                ZoomLocked);
            _isPanning = !_isDragZooming;
            _dragZoomAnchor = viewPoint;
            CaptureInput(isStylus);
            return;
        }

        if (PendingTagKind.HasValue)
        {
            PushUndo();
            Document.Tags.Add(new CanvasTag(PendingTagKind.Value, (float)docPoint.X, (float)docPoint.Y));
            PendingTagKind = null;
            MarkChanged(immediate: true);
            InvalidateVisual();
            return;
        }

        if (InsertSpaceModeEnabled && DirectInputPolicy.CanDragInsertSpace(isStylus ? DirectInputDevice.Pen : DirectInputDevice.Mouse))
        {
            BeginInsertSpace(viewPoint);
            CaptureInput(isStylus);
            return;
        }

        var tool = SelectedTool ?? ToolShelf.Defaults().First();
        // Direct mouse/touch content input is routed before this pen-tool path.
        if (!isStylus && tool.Kind is DrawingToolKind.Pen or DrawingToolKind.Highlighter or DrawingToolKind.Eraser)
        {
            return;
        }

        if (isStylus && DirectInputPolicy.PenContactTransformsSelection(
                tool.Kind,
                forceErase,
                _selectionBounds is { } selectedBounds && selectedBounds.Contains(docPoint)))
        {
            PushUndo();
            _isTransforming = true;
            _stylusSelectionTransformActive = true;
            _stylusSelectionTransformMoved = false;
            _resizeHandle = -1;
            _transformPoint = docPoint;
            CaptureInput(isStylus: true);
            InvalidateVisual();
            return;
        }

        switch (forceErase ? DrawingToolKind.Eraser : tool.Kind)
        {
            case DrawingToolKind.Shape:
                ClearSelection();
                _shapeStart = docPoint;
                _explicitShape = true;
                _activeStroke = new StrokeRecord(IdFactory.New("shape"), DrawingToolKind.Pen, tool.Color, tool.WidthMm, 100) { BrushProfile = "shape-v1" };
                _activeStroke.Points = ShapeGeometry.Points(tool.ShapeKind, docPoint, docPoint);
                PushInkUndo(_activeStroke.Id);
                Document.Strokes.Add(_activeStroke);
                CaptureInput(isStylus);
                break;
            case DrawingToolKind.Pen:
            case DrawingToolKind.Highlighter:
                ClearSelection();
                _strokeClock.Restart();
                _activeStroke = new StrokeRecord(IdFactory.New("stroke"), tool.Kind, tool.Color, tool.WidthMm, tool.OpacityPercent)
                {
                    PressurePercent = tool.PressurePercent,
                    InkFlowPercent = tool.InkFlowPercent,
                };
                AddStrokePoint(_activeStroke, docPoint, pressure, tiltX, tiltY);
                PushInkUndo(_activeStroke.Id);
                Document.Strokes.Add(_activeStroke);
                _shapeStart = docPoint;
                _pressureTravel.Begin(docPoint.X / UnitsPerMillimeter, docPoint.Y / UnitsPerMillimeter,
                    ResolvePressureEraserTool?.Invoke()?.PressureEraseTravelLimitMm ?? 1f);
                if (ResolveShapeSettings?.Invoke().HoldToStraightenEnabled == true) { _straighten.Begin(viewPoint, Environment.TickCount64); _straightenTimer.Start(); }
                CaptureInput(isStylus);
                UpdatePressureEraseCandidate(viewPoint, pressure, isStylus, tool);
                break;
            case DrawingToolKind.Eraser:
                PushUndo();
                _eraserViewPoint = viewPoint;
                var eraserTool = forceErase ? ResolvePressureEraserTool?.Invoke() ?? tool : tool;
                PerformErase(docPoint, eraserTool.EraserRadiusMm * UnitsPerMillimeter);
                CaptureInput(isStylus);
                break;
            case DrawingToolKind.Lasso:
                BeginLasso(docPoint);
                CaptureInput(isStylus);
                break;
        }
        InvalidateVisual();
    }

    private void ContinueMiddleNavigation(Point viewPoint, float pressure, float tiltX, float tiltY)
    {
        var useZoom = CanvasPointerFeedback.NavigationUsesRadialZoom(
            Keyboard.Modifiers.HasFlag(ModifierKeys.Control),
            ZoomLocked);
        if (useZoom != _isDragZooming)
        {
            _isDragZooming = useZoom;
            _isPanning = !useZoom;
            _dragZoomAnchor = viewPoint;
            _lastPointer = viewPoint;
        }
        ContinuePointer(viewPoint, pressure, tiltX, tiltY, isStylus: true);
    }

    private void BeginPanoramaSpringLoadedTool()
    {
        if (_panoramaSpringLoaded) return;
        _panoramaSpringLoaded = true;
        BeginSpringLoadedTool?.Invoke(SpringLoadedToolKind.PanoramaHand);
        UpdatePointerCursor();
    }

    private void EndPanoramaSpringLoadedTool()
    {
        if (!_panoramaSpringLoaded) return;
        _panoramaSpringLoaded = false;
        EndSpringLoadedTool?.Invoke(SpringLoadedToolKind.PanoramaHand);
        UpdatePointerCursor();
    }

    private void BeginInsertSpace(Point viewPoint)
    {
        if (Document == null) return;
        var docPoint = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        PushUndo();
        _insertSpaceUndoPushed = true;
        _isInsertingSpace = true;
        _insertStartY = _insertCurrentY = docPoint.Y;
        InvalidateVisual();
    }

    private void ContinuePointer(Point viewPoint, float pressure, float tiltX, float tiltY, bool isStylus)
    {
        if (Document == null) return;
        var docPoint = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        if (_isDragZooming)
        {
            var factor = ViewportState.DiagonalDragZoomFactor(_lastPointer, viewPoint);
            _viewport.ZoomAt((float)_dragZoomAnchor.X, (float)_dragZoomAnchor.Y, factor);
            _lastPointer = viewPoint;
            UpdateTextEditorPositions();
            InvalidateVisual();
            return;
        }
        if (_isPanning)
        {
            var sensitivity = Math.Clamp(PanoramaSensitivity, 0.25d, 3d);
            _viewport.Pan(
                (float)((viewPoint.X - _lastPointer.X) * sensitivity),
                (float)((viewPoint.Y - _lastPointer.Y) * sensitivity));
            _lastPointer = viewPoint;
            UpdateTextEditorPositions();
            InvalidateVisual();
            return;
        }
        if (_isInsertingSpace)
        {
            _insertCurrentY = docPoint.Y;
            InvalidateVisual();
            return;
        }
        if (_isTransforming && _selectionBounds.HasValue)
        {
            if (_resizeHandle < 0)
            {
                var dx = docPoint.X - _transformPoint.X;
                var dy = docPoint.Y - _transformPoint.Y;
                if (Math.Abs(dx) > 0.001 || Math.Abs(dy) > 0.001)
                {
                    TranslateSelection(dx, dy);
                    if (_stylusSelectionTransformActive) _stylusSelectionTransformMoved = true;
                }
            }
            else
            {
                ResizeSelection(docPoint);
            }
            _transformPoint = docPoint;
            InvalidateVisual();
            return;
        }
        if (_isLassoing)
        {
            ExtendLasso(docPoint);
            InvalidateVisual();
            return;
        }
        if (_temporaryPressureEraser)
        {
            _eraserViewPoint = viewPoint;
            PerformErase(docPoint, (_activePressureEraserTool?.EraserRadiusMm ?? ToolPreset.MinimumEraserRadiusMm) * UnitsPerMillimeter);
            InvalidateVisual();
            return;
        }
        if (_activeStroke != null)
        {
            if (isStylus && !_explicitShape) _pressureTravel.Observe(docPoint.X / UnitsPerMillimeter, docPoint.Y / UnitsPerMillimeter);
            if (_explicitShape || _straighten.Snapped)
            {
                _activeStroke.Points = ShapeGeometry.Points(_explicitShape ? SelectedTool?.ShapeKind ?? ShapeKind.Line : ShapeKind.Line, _shapeStart, docPoint);
                RefreshWetInk();
                return;
            }
            _straighten.Move(viewPoint, Environment.TickCount64, 2.5);
            var tool = SelectedTool;
            if (tool != null) UpdatePressureEraseCandidate(viewPoint, pressure, isStylus, tool);
            if (_pressureCandidate && Distance(viewPoint, _pressureStartView) > 2)
            {
                _pressureStartedAt = DateTime.UtcNow;
                _pressureStartView = viewPoint;
            }
            AddStrokePoint(_activeStroke, docPoint, pressure, tiltX, tiltY);
            RefreshWetInk();
            return;
        }
        if (_eraserViewPoint.HasValue && (SelectedTool?.Kind == DrawingToolKind.Eraser || _stylusEraserActive))
        {
            _eraserViewPoint = viewPoint;
            var eraserTool = SelectedTool?.Kind == DrawingToolKind.Eraser ? SelectedTool : ResolvePressureEraserTool?.Invoke();
            PerformErase(docPoint, (eraserTool?.EraserRadiusMm ?? ToolPreset.MinimumEraserRadiusMm) * UnitsPerMillimeter);
            InvalidateVisual();
        }
    }

    private void EndPointer(bool isStylus)
    {
        _pressureTravel.Reset();
        if (Document == null) return;
        _straightenTimer.Stop();
        _straighten.Cancel();
        _finishingPointer = true;
        ReleaseInput(isStylus);
        _finishingPointer = false;
        var changed = false;
        if (_isPanning)
        {
            _isPanning = false;
            ViewportMutated();
        }
        if (_isDragZooming)
        {
            _isDragZooming = false;
            ViewportMutated();
        }
        if (_isInsertingSpace)
        {
            _isInsertingSpace = false;
            var delta = _insertCurrentY - _insertStartY;
            if (Math.Abs(delta) > 2) { InsertVerticalSpace((float)_insertStartY, (float)delta); changed = true; }
            else if (_insertSpaceUndoPushed && _undo.Count > 0) _undo.Pop();
            _insertSpaceUndoPushed = false;
        }
        if (_isTransforming)
        {
            _isTransforming = false;
            RecomputeSelectionBounds();
            if (_stylusSelectionTransformActive && !_stylusSelectionTransformMoved)
            {
                if (_undo.Count > 0) _undo.Pop();
            }
            else
            {
                changed = true;
            }
            _stylusSelectionTransformActive = false;
            _stylusSelectionTransformMoved = false;
        }
        if (_isLassoing)
        {
            _isLassoing = false;
            ComputeLassoSelection();
        }
        if (_activeStroke != null)
        {
            _strokeClock.Stop();
            if (_activeStroke.Points.Count == 1)
            {
                var first = _activeStroke.Points[0];
                _activeStroke.Points.Add(new InkPoint(first.X + 0.01f, first.Y + 0.01f, first.Pressure, elapsedMilliseconds: first.ElapsedMilliseconds + 1));
                _activeStroke.InvalidateBounds();
            }
            if (_explicitShape && _activeStroke.Points.All(p => Distance(p.ToPoint(), _shapeStart) < 2))
            {
                Document.Strokes.Remove(_activeStroke);
                if (_undo.Count > 0) _undo.Pop();
            }
            _explicitShape = false;
            _activeStroke = null;
            _pressureEraseArmed = true;
            changed = true;
        }
        if (_eraserViewPoint.HasValue) changed = true;
        _eraserViewPoint = null;
        var endPressureEraserTool = _temporaryPressureEraser;
        _pressureCandidate = false;
        _pressureCandidateEraserTool = null;
        _temporaryPressureEraser = false;
        _activePressureEraserTool = null;
        _pressureTimer.Stop();
        if (changed) MarkChanged(immediate: true);
        if (endPressureEraserTool) EndSpringLoadedTool?.Invoke(SpringLoadedToolKind.Eraser);
        InvalidateVisual();
    }

    private void TryStraighten()
    {
        var settings = ResolveShapeSettings?.Invoke();
        if (_activeStroke == null || settings?.HoldToStraightenEnabled != true || _pressureCandidate || _temporaryPressureEraser) return;
        if (!_straighten.TrySnap(Environment.TickCount64, settings.StraightenDelayMillis)) return;
        var end = _activeStroke.Points[^1].ToPoint();
        _activeStroke.Points = ShapeGeometry.Points(ShapeKind.Line, _shapeStart, end);
        _activeStroke.BrushProfile = "shape-v1";
        if (_activeStroke.Kind == DrawingToolKind.Pen) _activeStroke.OpacityPercent = 100;
        _straightenTimer.Stop();
        InvalidateVisual();
    }

    public void CancelShapeAuthoring()
    {
        if (_activeStroke != null && (_explicitShape || _straighten.Active)) CancelStylusToolActionForNavigation();
    }

    private void CancelStylusToolActionForNavigation()
    {
        _pressureTravel.Reset();
        _straightenTimer.Stop(); _straighten.Cancel(); _explicitShape = false;
        var restoreDocument =
            _activeStroke != null ||
            _eraserViewPoint.HasValue ||
            _temporaryPressureEraser ||
            _isInsertingSpace ||
            _stylusSelectionTransformActive;
        var snapshot = restoreDocument && _undo.Count > 0 ? _undo.Pop() : null;

        _activeStroke = null;
        _eraserViewPoint = null;
        _temporaryPressureEraser = false;
        _pressureCandidateEraserTool = null;
        _activePressureEraserTool = null;
        _pressureCandidate = false;
        _pressureTimer.Stop();
        _isLassoing = false;
        _lasso.Clear();
        _isInsertingSpace = false;
        _insertSpaceUndoPushed = false;
        _isTransforming = false;
        _stylusSelectionTransformActive = false;
        _stylusSelectionTransformMoved = false;
        if (IsStylusCaptured) ReleaseStylusCapture();

        EndSpringLoadedTool?.Invoke(SpringLoadedToolKind.Eraser);
        if (snapshot != null) RestoreSnapshot(snapshot);
        else InvalidateVisual();
    }

    private void CaptureInput(bool isStylus)
    {
        if (isStylus) CaptureStylus();
        else CaptureMouse();
    }

    private void ReleaseInput(bool isStylus)
    {
        if (isStylus)
        {
            if (IsStylusCaptured) ReleaseStylusCapture();
        }
        else if (IsMouseCaptured)
        {
            ReleaseMouseCapture();
        }
    }

    private static bool IsPhysicalStylus(StylusDevice? device) =>
        device?.TabletDevice.Type == TabletDeviceType.Stylus;

    private static bool IsBarrelButton(StylusButton button) =>
        button.Guid == StylusPointProperties.BarrelButton.Id;

    private static bool HasPressedBarrelButton(StylusDevice? device) =>
        device?.StylusButtons.Cast<StylusButton>()
            .Any(button => IsBarrelButton(button) && button.StylusButtonState == StylusButtonState.Down) == true;

    private void CheckPressureEraseActivation()
    {
        if (_pressureTravel.Exceeded) return;
        var eraserTool = _pressureCandidateEraserTool;
        var activeStroke = _activeStroke;
        if (eraserTool == null || !PressureEraserPolicy.ShouldActivate(
                _pressureCandidate,
                activeStroke != null,
                (DateTime.UtcNow - _pressureStartedAt).TotalMilliseconds,
                eraserTool.PressureEraseDwellMilliseconds)) return;
        if (activeStroke == null) return;
        _straightenTimer.Stop(); _straighten.Cancel();
        Document?.Strokes.Remove(activeStroke);
        // Upgrade the append-only undo entry before this gesture can remove
        // existing ink. Ordinary writing never snapshots the whole document.
        if (_undo.Count > 0 && _undo.Peek().AddedStrokeId == activeStroke.Id) _undo.Pop();
        PushUndo();
        _activeStroke = null;
        _pressureCandidate = false;
        _pressureCandidateEraserTool = null;
        _pressureTimer.Stop();
        _activePressureEraserTool = BeginSpringLoadedTool?.Invoke(SpringLoadedToolKind.Eraser) ?? eraserTool;
        _temporaryPressureEraser = true;
        _eraserViewPoint = _pressureStartView;
        var documentPoint = _viewport.ViewToDocument(_pressureStartView.X, _pressureStartView.Y);
        PerformErase(documentPoint, _activePressureEraserTool.EraserRadiusMm * UnitsPerMillimeter);
        InvalidateVisual();
    }

    private void UpdatePressureEraseCandidate(Point viewPoint, float pressure, bool isStylus, ToolPreset tool)
    {
        var eraserTool = ResolvePressureEraserTool?.Invoke();
        var eligible = !_pressureTravel.Exceeded && PressureEraserPolicy.CanStartCandidate(
            isStylus,
            _pressureEraseArmed,
            tool.Kind,
            eraserTool?.PressureEraseEnabled == true,
            pressure,
            eraserTool?.PressureEraseThresholdPercent ?? 100);
        if (!eligible)
        {
            if (_pressureCandidate)
            {
                _pressureCandidate = false;
                _pressureTimer.Stop();
            }
            _pressureCandidateEraserTool = null;
            return;
        }
        if (_pressureCandidate) return;
        _pressureCandidate = true;
        _pressureCandidateEraserTool = eraserTool;
        _pressureStartView = viewPoint;
        _pressureStartedAt = DateTime.UtcNow;
        _pressureTimer.Start();
    }

    private void AddStrokePoint(StrokeRecord stroke, Point documentPoint, float pressure, float tiltX, float tiltY)
    {
        if (stroke.Points.Count > 0)
        {
            var previous = stroke.Points[^1];
            if (Math.Abs(previous.X - documentPoint.X) < 0.01 && Math.Abs(previous.Y - documentPoint.Y) < 0.01) return;
        }
        stroke.Points.Add(new InkPoint((float)documentPoint.X, (float)documentPoint.Y, pressure, tiltX, tiltY, _strokeClock.ElapsedMilliseconds));
        stroke.InvalidateBounds();
    }

    private void PerformErase(Point point, double radius)
    {
        if (Document == null) return;
        Document.Strokes.RemoveAll(stroke => PageScene.IsVisible(Document, stroke.LayerId) && StrokeHitsCircle(stroke, point, radius));
        Document.Tags.RemoveAll(tag => Distance(new Point(tag.DocumentX, tag.DocumentY), point) <= radius + 14);
        ClearSelection();
    }

    private static bool StrokeHitsCircle(StrokeRecord stroke, Point point, double radius)
    {
        if (!stroke.Bounds.IntersectsWith(new Rect(point.X - radius, point.Y - radius, radius * 2, radius * 2))) return false;
        if (stroke.VectorGeometry is { } geometry)
            return geometry.FillContainsWithDetail(new EllipseGeometry(point, radius, radius)) != IntersectionDetail.Empty;
        if (stroke.Points.Count == 1) return Distance(stroke.Points[0].ToPoint(), point) <= radius;
        for (var i = 1; i < stroke.Points.Count; i++)
        {
            if (SelectionGeometry.LineIntersectsCircle(stroke.Points[i - 1].ToPoint(), stroke.Points[i].ToPoint(), point, radius)) return true;
        }
        return false;
    }

    private void ComputeLassoSelection()
    {
        ClearSelection(keepLasso: true);
        if (Document == null || _lasso.Count < 3) { _lasso.Clear(); return; }
        _selectedStrokes.AddRange(Document.Strokes.Where(stroke => PageScene.IsVisible(Document, stroke.LayerId) && (!string.IsNullOrEmpty(stroke.VectorPath) ? SelectionGeometry.PolygonContainsRect(_lasso, stroke.Bounds) : stroke.Points.Count > 0 && stroke.Points.All(point => SelectionGeometry.PointInPolygonOrBoundary(point.ToPoint(), _lasso)))));
        _selectedTags.AddRange(Document.Tags.Where(tag => SelectionGeometry.PolygonContainsRect(_lasso, new Rect(tag.DocumentX - 12, tag.DocumentY - 12, 24, 24))));
        _selectedTextItems.AddRange(Document.TextItems.Where(text => PageScene.IsVisible(Document, text.LayerId) && SelectionGeometry.PolygonContainsRect(_lasso, new Rect(text.X, text.Y, text.Width, text.Height))));
        _selectedObjects.AddRange(Document.Objects.Where(item => PageScene.IsVisible(Document, item.LayerId) && !item.IsPageBackground && SelectionGeometry.PolygonContainsRect(_lasso, new Rect(item.X, item.Y, item.Width, item.Height))));
        _lasso.Clear();
        RecomputeSelectionBounds();
    }

    private void BeginLasso(Point documentPoint)
    {
        BeginLasso(documentPoint, SelectedTool?.LassoShape ?? LassoShape.Freeform);
    }

    private void BeginRightButtonLasso(Point viewPoint, bool usesStylus, bool hasStylusContact = false)
    {
        _stylusContextMenuTimer.Stop();
        if (_rightLassoArmed)
        {
            if (usesStylus && hasStylusContact && !_rightLassoHasStylusContact)
                ActivateRightButtonStylusContact(viewPoint);
            return;
        }
        _rightLassoArmed = true;
        _rightLassoActive = false;
        _rightLassoUsesStylus = usesStylus;
        _rightLassoHasStylusContact = usesStylus && hasStylusContact;
        _rightLassoStartView = viewPoint;
        _rightLassoLastView = viewPoint;
        _rightLassoArmedAtTick = Environment.TickCount64;
        _hoverViewPoint = viewPoint;
        CaptureInput(usesStylus);
        if (_rightLassoHasStylusContact) ActivateRightButtonStylusContact(viewPoint);
        UpdatePointerCursor();
        InvalidateVisual();
    }

    private void ActivateRightButtonStylusContact(Point viewPoint)
    {
        _rightLassoHasStylusContact = true;
        _rightLassoStartView = viewPoint;
        _rightLassoLastView = viewPoint;
        _hoverViewPoint = viewPoint;
        _isLassoing = true;
        _activeLassoShape = LassoShape.Freeform;
        _lasso.Clear();
        _lassoOrigin = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        _lasso.Add(_lassoOrigin);
        ArmStylusRightLassoHoldTimer();
        InvalidateVisual();
    }

    private void ArmStylusRightLassoHoldTimer()
    {
        _rightLassoHoldTimer.Stop();
        var clickWindow = (int)Math.Max(1u, GetDoubleClickTime());
        var elapsed = Math.Max(0, Environment.TickCount64 - _rightLassoArmedAtTick);
        if (CanvasPointerFeedback.StylusBarrelHoldStartsLasso(
                hasStylusContact: _rightLassoHasStylusContact,
                elapsedMilliseconds: elapsed,
                clickWindowMilliseconds: clickWindow))
        {
            ActivateTimedStylusRightLasso();
            return;
        }
        _rightLassoHoldTimer.Interval = TimeSpan.FromMilliseconds(Math.Max(1, clickWindow - elapsed));
        _rightLassoHoldTimer.Start();
    }

    private void ActivateTimedStylusRightLasso()
    {
        if (!_rightLassoArmed || !_rightLassoUsesStylus || !_rightLassoHasStylusContact || _rightLassoActive) return;
        _rightLassoActive = true;
        ClearSelection(keepLasso: true);
        InvalidateVisual();
    }

    private void ContinueRightButtonLasso(Point viewPoint)
    {
        if (!_rightLassoArmed) return;
        if (_rightLassoUsesStylus && !_rightLassoHasStylusContact)
        {
            _rightLassoLastView = viewPoint;
            _hoverViewPoint = viewPoint;
            return;
        }
        if (Distance(viewPoint, _rightLassoLastView) < 0.05) return;
        _rightLassoLastView = viewPoint;
        _hoverViewPoint = viewPoint;
        if (_rightLassoUsesStylus)
        {
            ExtendLasso(_viewport.ViewToDocument(viewPoint.X, viewPoint.Y));
            InvalidateVisual();
            return;
        }
        if (!_rightLassoActive)
        {
            var systemThreshold = Math.Max(SystemParameters.MinimumHorizontalDragDistance, SystemParameters.MinimumVerticalDragDistance);
            var startsLasso = CanvasPointerFeedback.RightButtonMovementStartsLasso(
                Distance(viewPoint, _rightLassoStartView),
                systemThreshold);
            if (!startsLasso) return;
            _rightLassoActive = true;
            BeginLasso(_viewport.ViewToDocument(_rightLassoStartView.X, _rightLassoStartView.Y), LassoShape.Freeform);
        }
        ExtendLasso(_viewport.ViewToDocument(viewPoint.X, viewPoint.Y));
        InvalidateVisual();
    }

    private void EndRightButtonLasso()
    {
        FinishRightButtonLasso(commitSelection: true, showContextMenu: true);
    }

    private void CancelRightButtonLasso()
    {
        FinishRightButtonLasso(commitSelection: false, showContextMenu: false);
    }

    private void FinishRightButtonLasso(bool commitSelection, bool showContextMenu)
    {
        if (!_rightLassoArmed) return;
        var wasActive = _rightLassoActive;
        var usesStylus = _rightLassoUsesStylus;
        var contextPoint = _rightLassoStartView;

        // Clear the state before releasing capture: WPF raises Lost*Capture
        // synchronously, and that callback must not re-enter this gesture.
        _rightLassoArmed = false;
        _rightLassoHoldTimer.Stop();
        _rightLassoActive = false;
        _rightLassoUsesStylus = false;
        _rightLassoHasStylusContact = false;
        _stylusBarrelButtonDown = false;
        _isLassoing = false;
        if (usesStylus)
        {
            _stylusActive = false;
            _stylusEraserActive = false;
        }

        if (wasActive && commitSelection) ComputeLassoSelection();
        else _lasso.Clear();
        ReleaseInput(usesStylus);
        UpdatePointerCursor();
        InvalidateVisual();
        if (showContextMenu && !wasActive)
        {
            if (usesStylus)
            {
                _pendingStylusContextMenuPoint = contextPoint;
                _stylusContextMenuTimer.Stop();
                _stylusContextMenuTimer.Start();
            }
            else
            {
                ShowDirectContextMenu(contextPoint);
            }
        }
    }

    private void BeginLasso(Point documentPoint, LassoShape shape)
    {
        ClearSelection();
        _isLassoing = true;
        _activeLassoShape = shape;
        _lassoOrigin = documentPoint;
        _lasso.Add(documentPoint);
    }

    private void ExtendLasso(Point documentPoint)
    {
        if (_activeLassoShape != LassoShape.Rectangle)
        {
            _lasso.Add(documentPoint);
            return;
        }
        var left = Math.Min(_lassoOrigin.X, documentPoint.X);
        var top = Math.Min(_lassoOrigin.Y, documentPoint.Y);
        var right = Math.Max(_lassoOrigin.X, documentPoint.X);
        var bottom = Math.Max(_lassoOrigin.Y, documentPoint.Y);
        _lasso.Clear();
        _lasso.AddRange(new[]
        {
            new Point(left, top),
            new Point(right, top),
            new Point(right, bottom),
            new Point(left, bottom),
        });
    }

    private void RecomputeSelectionBounds()
    {
        var bounds = Rect.Empty;
        foreach (var stroke in _selectedStrokes) bounds = bounds.IsEmpty ? stroke.Bounds : Rect.Union(bounds, stroke.Bounds);
        foreach (var tag in _selectedTags)
        {
            var rect = new Rect(tag.DocumentX - 12, tag.DocumentY - 12, 24, 24);
            bounds = bounds.IsEmpty ? rect : Rect.Union(bounds, rect);
        }
        foreach (var text in _selectedTextItems)
        {
            if (!SelectionGeometry.HasVisibleText(text.Text)) continue;
            var rect = new Rect(text.X, text.Y, text.Width, text.Height);
            bounds = bounds.IsEmpty ? rect : Rect.Union(bounds, rect);
        }
        foreach (var item in _selectedObjects)
        {
            var rect = new Rect(item.X, item.Y, item.Width, item.Height);
            bounds = bounds.IsEmpty ? rect : Rect.Union(bounds, rect);
        }
        _selectionBounds = bounds.IsEmpty ? null : bounds;
        UpdateTextEditorInteraction();
    }

    private void TranslateSelection(double dx, double dy)
    {
        foreach (var stroke in _selectedStrokes)
        {
            foreach (var point in stroke.Points) { point.X += (float)dx; point.Y += (float)dy; }
            stroke.TransformVector(new Matrix(1, 0, 0, 1, dx, dy));
            stroke.InvalidateBounds();
        }
        foreach (var tag in _selectedTags) { tag.DocumentX += (float)dx; tag.DocumentY += (float)dy; }
        foreach (var text in _selectedTextItems) { text.X += (float)dx; text.Y += (float)dy; }
        foreach (var item in _selectedObjects) { item.X += (float)dx; item.Y += (float)dy; }
        if (_selectionBounds is { } bounds) _selectionBounds = new Rect(bounds.X + dx, bounds.Y + dy, bounds.Width, bounds.Height);
        UpdateTextEditorPositions();
    }

    private void ScaleSelection(Point anchor, double factorX, double factorY)
    {
        foreach (var stroke in _selectedStrokes)
        {
            stroke.TransformVector(new Matrix(factorX, 0, 0, factorY, anchor.X * (1 - factorX), anchor.Y * (1 - factorY)));
            foreach (var point in stroke.Points)
            {
                var scaled = SelectionGeometry.Scale(point.X, point.Y, anchor.X, anchor.Y, factorX, factorY);
                point.X = (float)scaled.X; point.Y = (float)scaled.Y;
            }
            stroke.InvalidateBounds();
        }
        foreach (var tag in _selectedTags)
        {
            var scaled = SelectionGeometry.Scale(tag.DocumentX, tag.DocumentY, anchor.X, anchor.Y, factorX, factorY);
            tag.DocumentX = (float)scaled.X; tag.DocumentY = (float)scaled.Y; tag.Scale *= (float)Math.Sqrt(Math.Abs(factorX * factorY));
        }
        foreach (var item in _selectedObjects)
        {
            var scaled = SelectionGeometry.Scale(item.X, item.Y, anchor.X, anchor.Y, factorX, factorY);
            item.X = (float)scaled.X; item.Y = (float)scaled.Y; item.Width *= (float)factorX; item.Height *= (float)factorY;
        }
        foreach (var text in _selectedTextItems)
        {
            var scaled = SelectionGeometry.Scale(text.X, text.Y, anchor.X, anchor.Y, factorX, factorY);
            text.X = (float)scaled.X;
            text.Y = (float)scaled.Y;
            text.Width *= (float)factorX;
            text.Height *= (float)factorY;
            text.FontSize *= Math.Sqrt(Math.Abs(factorX * factorY));
        }
        UpdateTextEditorPositions();
    }

    private void ResizeSelection(Point documentPoint)
    {
        if (_selectionBounds is not { } current || _resizeHandle is < 0 or > 7) return;
        var resized = SelectionGeometry.ResizeBounds(current, _resizeHandle, documentPoint, 24 / Math.Max(0.01, _viewport.Scale));
        if (resized == current || current.Width <= 0 || current.Height <= 0) return;
        var anchor = OppositeHandlePoint(current, _resizeHandle);
        var factorX = resized.Width / current.Width;
        var factorY = resized.Height / current.Height;
        ScaleSelection(anchor, factorX, factorY);
        RecomputeSelectionBounds();
    }

    private void InsertVerticalSpace(float startY, float deltaY)
    {
        if (Document == null) return;
        foreach (var stroke in Document.Strokes.Where(item => item.Bounds.Top >= startY))
        {
            foreach (var point in stroke.Points) point.Y += deltaY;
            stroke.TransformVector(new Matrix(1, 0, 0, 1, 0, deltaY));
            stroke.InvalidateBounds();
        }
        foreach (var tag in Document.Tags.Where(item => item.DocumentY >= startY)) tag.DocumentY += deltaY;
        foreach (var text in Document.TextItems.Where(item => item.Y >= startY)) text.Y += deltaY;
        foreach (var item in Document.Objects.Where(item => item.Y >= startY)) item.Y += deltaY;
        UpdateTextEditorPositions();
    }

    private void AddTemporaryTextItem(Point documentPoint)
    {
        if (Document == null) return;
        var item = new TextItem { X = (float)documentPoint.X, Y = (float)documentPoint.Y };
        var editor = CreateTextEditor(item);
        _temporaryTextItem = item;
        _temporaryTextEditor = editor;
        _temporaryTextCommitted = false;
        _temporaryTextUndoDepth = _undo.Count;
        EditorLayer.Children.Add(editor);
        PositionEditor(editor, item);
        SelectOnlyText(item);
        editor.IsHitTestVisible = true;
        editor.Focus();
        editor.CaretIndex = 0;
        InvalidateVisual();
    }

    private void AbandonTemporaryText()
    {
        var item = _temporaryTextItem;
        var editor = _temporaryTextEditor;
        if (item == null || editor == null) return;
        _temporaryTextItem = null;
        _temporaryTextEditor = null;
        var committed = _temporaryTextCommitted;
        var undoDepth = _temporaryTextUndoDepth;
        _temporaryTextCommitted = false;
        _temporaryTextUndoDepth = 0;
        if (committed && !string.IsNullOrWhiteSpace(item.Text)) return;
        if (committed)
        {
            Document?.TextItems.Remove(item);
            while (_undo.Count > undoDepth) _undo.Pop();
        }
        _selectedTextItems.Remove(item);
        EditorLayer.Children.Remove(editor);
        RecomputeSelectionBounds();
        if (committed) MarkChanged(immediate: true);
    }

    private void RebuildTextEditors()
    {
        _suppressEditorChange = true;
        _temporaryTextItem = null;
        _temporaryTextEditor = null;
        _temporaryTextCommitted = false;
        _temporaryTextUndoDepth = 0;
        EditorLayer.Children.Clear();
        if (Document != null)
        {
            foreach (var item in Document.TextItems)
            {
                var editor = CreateTextEditor(item);
                EditorLayer.Children.Add(editor);
                PositionEditor(editor, item);
            }
        }
        _suppressEditorChange = false;
        UpdateTextEditorInteraction();
    }

    private TextBox CreateTextEditor(TextItem item)
    {
        var editor = new TextBox
        {
            Tag = item,
            Text = item.Text,
            Width = item.Width,
            MinHeight = item.Height,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            Background = ParseBrush(item.BackgroundHex, Brushes.Transparent),
            BorderThickness = new Thickness(0),
            Padding = new Thickness(2),
            CaretBrush = Appearance.Theme == PageTheme.Dark ? Brushes.White : Brushes.Black,
        };
        ApplyTextItemStyle(editor, item);
        editor.GotKeyboardFocus += (_, _) => { editor.Opacity = 1; InvalidateVisual(); };
        editor.TextChanged += (_, _) =>
        {
            if (_suppressEditorChange) return;
            if (ReferenceEquals(_temporaryTextItem, item) && !_temporaryTextCommitted)
            {
                item.Text = editor.Text;
                if (string.IsNullOrWhiteSpace(item.Text)) return;
                PushUndo();
                Document?.TextItems.Add(item);
                _temporaryTextCommitted = true;
            }
            item.Text = editor.Text;
            item.Height = (float)Math.Max(32, editor.ActualHeight);
            if (_selectedTextItems.Contains(item)) RecomputeSelectionBounds();
            MarkChanged();
        };
        editor.LostKeyboardFocus += (_, _) =>
        {
            if (Document?.Layers.Count > 0) { editor.Opacity = 0; InvalidateVisual(); }
            editor.IsHitTestVisible = false;
            if (ReferenceEquals(_temporaryTextItem, item))
            {
                if (string.IsNullOrWhiteSpace(item.Text)) AbandonTemporaryText();
                else
                {
                    _temporaryTextItem = null;
                    _temporaryTextEditor = null;
                    _temporaryTextCommitted = false;
                    _temporaryTextUndoDepth = 0;
                }
                return;
            }
            if (!string.IsNullOrWhiteSpace(item.Text)) return;
            Document?.TextItems.Remove(item);
            EditorLayer.Children.Remove(editor);
            MarkChanged(immediate: true);
        };
        return editor;
    }

    private void ApplyTextItemStyle(TextBox editor, TextItem item)
    {
        editor.FontFamily = new FontFamily(item.FontFamily);
        editor.FontSize = item.FontSize * (item.Subscript ? 0.8 : 1) * _viewport.Scale;
        editor.FontWeight = item.Bold ? FontWeights.Bold : FontWeights.Normal;
        editor.FontStyle = item.Italic ? FontStyles.Italic : FontStyles.Normal;
        editor.TextDecorations = DecorationsFor(item);
        editor.Foreground = TextItemBrush(item, Appearance.Theme == PageTheme.Dark, false);
        editor.Background = ParseBrush(item.BackgroundHex, Brushes.Transparent);
        editor.TextAlignment = ParseAlignment(item.Alignment);
    }

    private void UpdateTextEditorInteraction()
    {
        foreach (TextBox editor in EditorLayer.Children)
        {
            editor.IsReadOnly = false;
            editor.IsHitTestVisible = editor.IsKeyboardFocusWithin;
            editor.Cursor = Cursors.IBeam;
            editor.Opacity = Document?.Layers.Count > 0 && !editor.IsKeyboardFocusWithin && !ReferenceEquals(editor, _temporaryTextEditor) ? 0 : 1;
        }
    }

    private void UpdateTextEditorAppearance()
    {
        foreach (TextBox editor in EditorLayer.Children)
        {
            if (editor.Tag is TextItem item)
            {
                editor.Foreground = TextItemBrush(item, Appearance.Theme == PageTheme.Dark, false);
                editor.Background = ParseBrush(item.BackgroundHex, Brushes.Transparent);
            }
        }
    }

    private void UpdateTextEditorPositions()
    {
        foreach (TextBox editor in EditorLayer.Children)
        {
            if (editor.Tag is TextItem item) PositionEditor(editor, item);
        }
    }

    private void PositionEditor(TextBox editor, TextItem item)
    {
        var view = _viewport.DocumentToView(item.X, item.Y);
        Canvas.SetLeft(editor, view.X);
        Canvas.SetTop(editor, view.Y);
        editor.Width = Math.Max(80, item.Width * _viewport.Scale);
        editor.FontSize = item.FontSize * (item.Subscript ? 0.8 : 1) * _viewport.Scale;
        editor.Padding = new Thickness(2 + item.IndentLevel * 18 * _viewport.Scale, 2 + (item.Subscript ? item.FontSize * 0.25 * _viewport.Scale : 0), 2, 2);
    }

    protected override void OnRender(DrawingContext dc)
    {
        base.OnRender(dc);
        PointerOverlay.InvalidateVisual();
        WetInkOverlay.InvalidateVisual();
        SceneLayer.InvalidateVisual();
        dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(10, 10, 10)), null, new Rect(0, 0, ActualWidth, ActualHeight));
        if (Document == null)
        {
            DrawText(dc, "选择或新建一个页面", new Point(48, 48), 18, new SolidColorBrush(Color.FromRgb(185, 185, 185)));
            return;
        }
    }

    // Imported layers and tags can cover ink; keep those scenes in their
    // canonical painter order instead of moving a lower stroke to an overlay.
    private bool SeparateWetInk => _activeStroke != null && Document is { Layers.Count: 0, Tags.Count: 0 };

    private void RefreshWetInk()
    {
        if (SeparateWetInk) { WetInkOverlay.InvalidateVisual(); PointerOverlay.InvalidateVisual(); }
        else InvalidateVisual();
    }

    internal void DrawWetInk(DrawingContext dc)
    {
        if (!SeparateWetInk) return;
        dc.PushTransform(new MatrixTransform(_viewport.DocumentToViewMatrix()));
        var stroke = _activeStroke!;
        dc.DrawDrawing(_strokeDrawings.Get(stroke, Appearance.Theme == PageTheme.Dark, false,
            context => DrawStroke(context, stroke, lightExport: false)));
        dc.Pop();
    }

    internal void DrawScreenScene(DrawingContext dc)
    {
        if (Document == null) return;
        SceneRenderCount++;
        _drawingDryScene = SeparateWetInk;
        try
        {
            dc.PushTransform(new MatrixTransform(_viewport.DocumentToViewMatrix()));
            DrawDocument(dc, CanvasRenderLayer.Composite, lightExport: false, VisibleDocumentBounds());
            dc.Pop();
        }
        finally { _drawingDryScene = false; }
    }

    private void DrawDocument(DrawingContext dc, CanvasRenderLayer layer, bool lightExport, Rect extent)
    {
        if (Document == null) return;
        if (layer is CanvasRenderLayer.Background or CanvasRenderLayer.Composite) DrawPaper(dc, lightExport, extent);
        if (layer == CanvasRenderLayer.Composite && Document.Layers.Count > 0)
        {
            foreach (var content in PageScene.OrderedContent(Document, extent))
            {
                switch (content)
                {
                    case PageObject item: DrawObjects(dc, lightExport, extent, [item]); break;
                    case StrokeRecord stroke: DrawStrokes(dc, lightExport, extent, [stroke]); break;
                    case TextItem text: DrawTextItems(dc, lightExport, [text]); break;
                }
            }
            DrawTags(dc, lightExport);
            return;
        }
        if (layer is CanvasRenderLayer.Inserts or CanvasRenderLayer.Composite) DrawObjects(dc, lightExport, extent);
        if (layer is CanvasRenderLayer.InkAndText or CanvasRenderLayer.Composite)
        {
            DrawStrokes(dc, lightExport, extent);
            DrawTags(dc, lightExport);
            if (lightExport) DrawTextItems(dc, lightExport);
        }
    }

    private void DrawPaper(DrawingContext dc, bool lightExport, Rect extent)
    {
        var paper = lightExport ? Brushes.White : ParseBrush(Appearance.PageColor, Appearance.Theme == PageTheme.Dark ? Color.FromRgb(27, 27, 27) : Color.FromRgb(250, 250, 250));
        if (Appearance.PaperKind == PaperKind.Infinite)
        {
            var rect = new Rect(0, 0, Appearance.InfiniteWidth, Appearance.InfiniteHeight);
            dc.DrawRectangle(paper, lightExport ? null : new Pen(new SolidColorBrush(Color.FromRgb(61, 61, 61)), 1 / Math.Max(0.1, _viewport.Scale)), rect);
            var visiblePaper = Rect.Intersect(rect, extent);
            if (!visiblePaper.IsEmpty) DrawPattern(dc, visiblePaper, lightExport);
        }
        else
        {
            var sheets = Math.Max(1, Appearance.SheetCount);
            var stride = Appearance.Height + A4PageLayout.PageGap;
            var firstSheet = Math.Max(0, (int)Math.Floor(extent.Top / stride));
            var lastSheet = Math.Min(sheets - 1, (int)Math.Floor(Math.Max(extent.Top, extent.Bottom) / stride));
            for (var i = firstSheet; i <= lastSheet; i++)
            {
                var rect = new Rect(0, i * (Appearance.Height + A4PageLayout.PageGap), Appearance.Width, Appearance.Height);
                if (!rect.IntersectsWith(extent)) continue;
                dc.DrawRectangle(paper, new Pen(new SolidColorBrush(Color.FromRgb(61, 61, 61)), 1 / Math.Max(0.1, _viewport.Scale)), rect);
                DrawPattern(dc, rect, lightExport);
            }
        }
        if (Appearance.ShowTitle && !string.IsNullOrWhiteSpace(PageTitle) && !(lightExport && Appearance.PaperKind == PaperKind.Infinite))
        {
            DrawText(dc, PageTitle, new Point(54, 42), 22, lightExport ? Brushes.Black : TextBrush(InkPalette.Black, Appearance.Theme == PageTheme.Dark, false), true);
        }
    }

    private void DrawPattern(DrawingContext dc, Rect bounds, bool lightExport)
    {
        if (Appearance.Background == CanvasBackgroundFormat.Blank) return;
        var spacing = Appearance.Background switch
        {
            CanvasBackgroundFormat.RuledNarrow or CanvasBackgroundFormat.GridDense or CanvasBackgroundFormat.DotsDense => 24d,
            CanvasBackgroundFormat.RuledWide or CanvasBackgroundFormat.GridWide or CanvasBackgroundFormat.DotsWide => 48d,
            _ => 32d,
        };
        var color = lightExport ? Color.FromRgb(215, 224, 232) : Color.FromRgb(45, 50, 56);
        var pen = new Pen(new SolidColorBrush(color), lightExport ? 0.7 : 0.8 / Math.Max(0.1, _viewport.Scale));
        var isRuled = Appearance.Background is CanvasBackgroundFormat.RuledNarrow or CanvasBackgroundFormat.RuledStandard or CanvasBackgroundFormat.RuledWide;
        var isGrid = Appearance.Background is CanvasBackgroundFormat.GridDense or CanvasBackgroundFormat.GridStandard or CanvasBackgroundFormat.GridWide;
        var isDots = Appearance.Background is CanvasBackgroundFormat.DotsDense or CanvasBackgroundFormat.DotsStandard or CanvasBackgroundFormat.DotsWide;
        if (isRuled || isGrid)
        {
            for (var y = Math.Ceiling(bounds.Top / spacing) * spacing; y <= bounds.Bottom; y += spacing) dc.DrawLine(pen, new Point(bounds.Left, y), new Point(bounds.Right, y));
        }
        if (isGrid)
        {
            for (var x = Math.Ceiling(bounds.Left / spacing) * spacing; x <= bounds.Right; x += spacing) dc.DrawLine(pen, new Point(x, bounds.Top), new Point(x, bounds.Bottom));
        }
        if (isDots)
        {
            var brush = new SolidColorBrush(color);
            for (var y = Math.Ceiling(bounds.Top / spacing) * spacing; y <= bounds.Bottom; y += spacing)
            for (var x = Math.Ceiling(bounds.Left / spacing) * spacing; x <= bounds.Right; x += spacing)
                dc.DrawEllipse(brush, null, new Point(x, y), 1, 1);
        }
    }

    private void DrawObjects(DrawingContext dc, bool lightExport, Rect extent, IEnumerable<PageObject>? items = null)
    {
        if (Document == null) return;
        foreach (var item in items ?? Document.Objects)
        {
            if (!PageScene.IsVisible(Document, item.LayerId)) continue;
            var rect = new Rect(item.X, item.Y, item.Width, item.Height);
            if (!rect.IntersectsWith(extent)) continue;
            var border = new Pen(new SolidColorBrush(lightExport ? Color.FromRgb(180, 180, 180) : Color.FromRgb(87, 87, 87)), 1);
            switch (item.Kind)
            {
                case PageObjectKind.Image:
                case PageObjectKind.Sticker:
                    var bitmap = LoadBitmap(item.AssetName, item.ViewInverted && !lightExport);
                    if (bitmap != null) DrawTransformedImage(dc, bitmap, rect, item);
                    else { dc.DrawRectangle(lightExport ? Brushes.WhiteSmoke : new SolidColorBrush(Color.FromRgb(31, 31, 31)), border, rect); DrawText(dc, $"图片不可用 · {item.DisplayName}", new Point(rect.X + 12, rect.Y + 12), 12, lightExport ? Brushes.Black : Brushes.White); }
                    break;
                case PageObjectKind.PdfPrintout:
                    var pdfBitmap = LoadAdaptivePdfBitmap(item, item.ViewInverted && !lightExport, lightExport);
                    if (pdfBitmap != null) DrawTransformedImage(dc, pdfBitmap, rect, item);
                    else { dc.DrawRectangle(lightExport ? Brushes.WhiteSmoke : new SolidColorBrush(Color.FromRgb(31, 31, 31)), border, rect); DrawText(dc, $"PDF 页面不可用 · {item.DisplayName}", new Point(rect.X + 12, rect.Y + 12), 12, lightExport ? Brushes.Black : Brushes.White); }
                    break;
                case PageObjectKind.Table:
                    DrawTable(dc, item, rect, lightExport, border);
                    break;
                case PageObjectKind.Spreadsheet:
                    DrawSpreadsheet(dc, item, rect, lightExport, border);
                    break;
                case PageObjectKind.Attachment:
                    dc.DrawRoundedRectangle(lightExport ? Brushes.WhiteSmoke : new SolidColorBrush(Color.FromRgb(31, 31, 31)), border, rect, 3, 3);
                    dc.DrawRoundedRectangle(new SolidColorBrush(Color.FromRgb(218, 178, 233)), null, new Rect(rect.X + 10, rect.Y + 10, 34, rect.Height - 20), 3, 3);
                    DrawText(dc, item.DisplayName, new Point(rect.X + 54, rect.Y + 11), 13, lightExport ? Brushes.Black : Brushes.White, true);
                    DrawText(dc, $"{Math.Max(1, item.SizeBytes / 1024)} KB · 已嵌入", new Point(rect.X + 54, rect.Y + 32), 10, lightExport ? Brushes.DimGray : new SolidColorBrush(Color.FromRgb(185, 185, 185)));
                    break;
                case PageObjectKind.NoteLink:
                    DrawText(dc, $"↗ {item.DisplayName}", new Point(rect.X, rect.Y), 14, new SolidColorBrush(Color.FromRgb(86, 156, 214)), underline: true);
                    break;
            }
        }
    }

    private static void DrawTransformedImage(DrawingContext dc, BitmapSource bitmap, Rect destination, PageObject item)
    {
        var turns = ((item.RotationQuarterTurns % 4) + 4) % 4;
        var center = new Point(destination.Left + destination.Width / 2, destination.Top + destination.Height / 2);
        var local = turns % 2 == 0
            ? new Rect(-destination.Width / 2, -destination.Height / 2, destination.Width, destination.Height)
            : new Rect(-destination.Height / 2, -destination.Width / 2, destination.Height, destination.Width);
        dc.PushTransform(new TranslateTransform(center.X, center.Y));
        dc.PushTransform(new ScaleTransform(item.FlipHorizontal ? -1 : 1, item.FlipVertical ? -1 : 1));
        dc.PushTransform(new RotateTransform(turns * 90));
        dc.DrawImage(bitmap, local);
        dc.Pop();
        dc.Pop();
        dc.Pop();
    }

    private void DrawTable(DrawingContext dc, PageObject item, Rect rect, bool light, Pen border)
    {
        var rows = Math.Max(1, item.Rows);
        var columns = Math.Max(1, item.Columns);
        dc.DrawRectangle(light ? Brushes.White : new SolidColorBrush(Color.FromRgb(31, 31, 31)), border, rect);
        var cellWidth = rect.Width / columns;
        var cellHeight = rect.Height / rows;
        for (var c = 1; c < columns; c++) dc.DrawLine(border, new Point(rect.X + c * cellWidth, rect.Y), new Point(rect.X + c * cellWidth, rect.Bottom));
        for (var r = 1; r < rows; r++) dc.DrawLine(border, new Point(rect.X, rect.Y + r * cellHeight), new Point(rect.Right, rect.Y + r * cellHeight));
        for (var index = 0; index < Math.Min(item.Cells.Count, rows * columns); index++)
        {
            var row = index / columns; var column = index % columns;
            DrawText(dc, item.Cells[index], new Point(rect.X + column * cellWidth + 5, rect.Y + row * cellHeight + 6), 11, light ? Brushes.Black : Brushes.White);
        }
    }

    private void DrawSpreadsheet(DrawingContext dc, PageObject item, Rect rect, bool light, Pen border)
    {
        dc.DrawRectangle(light ? Brushes.White : new SolidColorBrush(Color.FromRgb(31, 31, 31)), border, rect);
        var header = new Rect(rect.X, rect.Y, rect.Width, Math.Min(34, rect.Height));
        dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(28, 94, 57)), null, header);
        DrawText(dc, item.DisplayName, new Point(rect.X + 10, rect.Y + 8), 12, Brushes.White, true);
        var preview = item.PreviewRows.Take(5).ToList();
        if (preview.Count == 0) return;
        var columns = Math.Max(1, preview.Max(row => row.Count));
        var cellWidth = rect.Width / columns;
        var cellHeight = Math.Max(22, (rect.Height - header.Height) / preview.Count);
        for (var r = 0; r < preview.Count; r++)
        for (var c = 0; c < preview[r].Count; c++)
            DrawText(dc, preview[r][c], new Point(rect.X + c * cellWidth + 5, header.Bottom + r * cellHeight + 4), 10, light ? Brushes.Black : Brushes.White);
    }

    private void DrawStrokes(DrawingContext dc, bool lightExport, Rect extent, IEnumerable<StrokeRecord>? strokes = null)
    {
        if (Document == null) return;
        foreach (var stroke in strokes ?? Document.Strokes)
        {
            if (_drawingDryScene && ReferenceEquals(stroke, _activeStroke)) continue;
            if (!PageScene.IsVisible(Document, stroke.LayerId) || !StrokeViewportCulling.IsVisible(stroke, extent)) continue;
            // Imported vectors already retain their geometry. Wet ink is the only
            // freehand stroke whose shape is rebuilt while the nib moves.
            if (stroke != _activeStroke && string.IsNullOrEmpty(stroke.VectorPath))
                dc.DrawDrawing(_strokeDrawings.Get(stroke, Appearance.Theme == PageTheme.Dark, lightExport,
                    context => DrawStroke(context, stroke, lightExport)));
            else DrawStroke(dc, stroke, lightExport);
        }
    }

    private void DrawStroke(DrawingContext dc, StrokeRecord stroke, bool lightExport)
    {
            var brush = TextBrush(stroke.Color, Appearance.Theme == PageTheme.Dark, lightExport);
            if (!string.IsNullOrEmpty(stroke.ColorHex))
            {
                var sourceColor = (Color)ColorConverter.ConvertFromString(stroke.ColorHex);
                // Only logical black adapts to dark paper. Whites and arbitrary
                // source colors remain source colors, including white cover ink.
                if (sourceColor.R != 0 || sourceColor.G != 0 || sourceColor.B != 0 || lightExport || Appearance.Theme != PageTheme.Dark)
                    brush = new SolidColorBrush(Color.FromRgb(sourceColor.R, sourceColor.G, sourceColor.B));
            }
            if (stroke.VectorGeometry is { } vector)
            {
                dc.PushOpacity(Math.Clamp(stroke.OpacityPercent / 100d, 0, 1));
                dc.DrawGeometry(brush, null, vector);
                dc.Pop();
                return;
            }
            if (stroke.Points.Count == 0) return;
            if (stroke.BrushProfile == "shape-v1")
            {
                dc.PushOpacity(Math.Clamp(stroke.OpacityPercent / 100d, 0, 1));
                DrawPolyline(dc, stroke.Points, new Pen(brush, stroke.WidthMm * UnitsPerMillimeter) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, LineJoin = PenLineJoin.Round });
                dc.Pop();
                return;
            }
            if (stroke.Kind == DrawingToolKind.Highlighter)
            {
                var color = ((SolidColorBrush)brush).Color;
                var alpha = (byte)Math.Clamp(255 * stroke.OpacityPercent / 100, 1, 255);
                var pen = new Pen(new SolidColorBrush(Color.FromArgb(alpha, color.R, color.G, color.B)), stroke.WidthMm * UnitsPerMillimeter)
                { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, LineJoin = PenLineJoin.Round };
                DrawPolyline(dc, stroke.Points, pen);
                return;
            }

            dc.DrawGeometry(brush, null, FountainStrokeGeometry.Create(stroke));
    }

    private static void DrawPolyline(DrawingContext dc, IReadOnlyList<InkPoint> points, Pen pen)
    {
        if (points.Count == 1) { dc.DrawEllipse(pen.Brush, null, points[0].ToPoint(), pen.Thickness / 2, pen.Thickness / 2); return; }
        var geometry = new StreamGeometry();
        using (var context = geometry.Open())
        {
            context.BeginFigure(points[0].ToPoint(), false, false);
            for (var i = 1; i < points.Count; i++) context.LineTo(points[i].ToPoint(), true, false);
        }
        geometry.Freeze();
        dc.DrawGeometry(null, pen, geometry);
    }

    private void DrawTags(DrawingContext dc, bool lightExport)
    {
        if (Document == null) return;
        var brush = new SolidColorBrush(Color.FromRgb(229, 72, 77));
        foreach (var tag in Document.Tags)
        {
            var center = new Point(tag.DocumentX, tag.DocumentY);
            if (tag.Kind == CanvasTagKind.Question) DrawText(dc, "?", new Point(center.X - 6, center.Y - 15), 22 * tag.Scale, brush, true);
            else DrawStar(dc, center, 10 * tag.Scale, brush);
        }
    }

    private void DrawTextItems(DrawingContext dc, bool lightExport, IEnumerable<TextItem>? items = null)
    {
        if (Document == null) return;
        foreach (var item in items ?? Document.TextItems)
        {
            if (!PageScene.IsVisible(Document, item.LayerId)) continue;
            if (!lightExport && EditorLayer.Children.OfType<TextBox>().Any(editor => ReferenceEquals(editor.Tag, item) && editor.IsKeyboardFocusWithin)) continue;
            var background = ParseBrush(item.BackgroundHex, Brushes.Transparent);
            if (background != Brushes.Transparent) dc.DrawRectangle(background, null, new Rect(item.X, item.Y, item.Width, item.Height));
            var formatted = new FormattedText(item.Text ?? string.Empty, CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
                new Typeface(new FontFamily(item.FontFamily), item.Italic ? FontStyles.Italic : FontStyles.Normal, item.Bold ? FontWeights.Bold : FontWeights.Normal, FontStretches.Normal),
                item.FontSize * (item.Subscript ? 0.8 : 1), TextItemBrush(item, Appearance.Theme == PageTheme.Dark, lightExport), VisualTreeHelper.GetDpi(this).PixelsPerDip)
            {
                MaxTextWidth = Math.Max(1, item.Width - item.IndentLevel * 18),
                TextAlignment = ParseAlignment(item.Alignment),
                LineHeight = Math.Max(1, item.FontSize * item.LineSpacing),
            };
            formatted.SetTextDecorations(DecorationsFor(item));
            dc.DrawText(formatted, new Point(item.X + item.IndentLevel * 18, item.Y + (item.Subscript ? item.FontSize * 0.25 : 0)));
        }
    }

    private void DrawOverlays(DrawingContext dc)
    {
        if (_isLassoing && _lasso.Count > 1)
        {
            var pen = new Pen(new SolidColorBrush(Color.FromRgb(238, 238, 238)), 1.1) { DashStyle = new DashStyle(new[] { 3d, 3d }, 0) };
            var geometry = new StreamGeometry();
            using var context = geometry.Open();
            context.BeginFigure(_viewport.DocumentToView(_lasso[0].X, _lasso[0].Y), false, false);
            for (var i = 1; i < _lasso.Count; i++) context.LineTo(_viewport.DocumentToView(_lasso[i].X, _lasso[i].Y), true, false);
            if (_activeLassoShape == LassoShape.Rectangle && _lasso.Count >= 4) context.LineTo(_viewport.DocumentToView(_lasso[0].X, _lasso[0].Y), true, false);
            dc.DrawGeometry(null, pen, geometry);
        }
        if (_selectionBounds is { } bounds)
        {
            var view = _viewport.DocumentToView(bounds);
            var frame = new Pen(new SolidColorBrush(Color.FromRgb(238, 238, 238)), 1) { DashStyle = new DashStyle(new[] { 3d, 3d }, 0) };
            dc.DrawRectangle(null, frame, view);
            var handleFill = new SolidColorBrush(Color.FromRgb(245, 245, 245));
            var handleStroke = new Pen(new SolidColorBrush(Color.FromRgb(86, 86, 86)), 1);
            foreach (var point in SelectionGeometry.SelectionHandles(view))
                dc.DrawRectangle(handleFill, handleStroke, new Rect(point.X - 4.5, point.Y - 4.5, 9, 9));
        }
        if (_eraserViewPoint is { } eraser)
        {
            var eraserTool = _activePressureEraserTool ?? (SelectedTool?.Kind == DrawingToolKind.Eraser ? SelectedTool : ResolvePressureEraserTool?.Invoke());
            var radius = (eraserTool?.EraserRadiusMm ?? ToolPreset.MinimumEraserRadiusMm) * UnitsPerMillimeter * _viewport.Scale;
            dc.DrawEllipse(null, new Pen(new SolidColorBrush(Color.FromRgb(185, 185, 185)), 1.2), eraser, radius, radius);
        }
        if (_isInsertingSpace)
        {
            var start = _viewport.DocumentToView(0, _insertStartY).Y;
            var current = _viewport.DocumentToView(0, _insertCurrentY).Y;
            var pen = new Pen(new SolidColorBrush(Color.FromRgb(218, 178, 233)), 1.2) { DashStyle = DashStyles.Dash };
            dc.DrawLine(pen, new Point(0, start), new Point(ActualWidth, start));
            dc.DrawLine(pen, new Point(0, current), new Point(ActualWidth, current));
        }
    }

    internal void DrawPointerFeedback(DrawingContext dc)
    {
        if (Document == null) return;
        DrawOverlays(dc);
        if (!InsertSpaceModeEnabled && _hoverViewPoint is { } hover && CurrentPointerCursorMode() == CanvasPointerCursorMode.PenDot)
        {
            var fill = TextBrush(SelectedTool?.Color ?? InkPalette.Black, Appearance.Theme == PageTheme.Dark, false);
            var outline = new Pen(Appearance.Theme == PageTheme.Dark ? Brushes.White : Brushes.Black, 1);
            dc.DrawEllipse(fill, outline, hover, 2.25, 2.25);
        }
        else if (!_isInsertingSpace && InsertSpaceModeEnabled && _hoverViewPoint is { } insertHover &&
                 DirectInputPolicy.ShowsInsertSpaceHover(_stylusInRange ? DirectInputDevice.Pen : DirectInputDevice.Mouse))
        {
            var pen = new Pen(new SolidColorBrush(Color.FromRgb(218, 178, 233)), 1.2) { DashStyle = DashStyles.Dash };
            dc.DrawLine(pen, new Point(0, insertHover.Y), new Point(ActualWidth, insertHover.Y));
        }
    }

    private void UpdatePointerCursor()
    {
        if (_rightLassoArmed)
        {
            Cursor = Cursors.Cross;
            return;
        }
        Cursor = CurrentPointerCursorMode() switch
        {
            CanvasPointerCursorMode.Hand => Cursors.Hand,
            CanvasPointerCursorMode.PenDot => Cursors.None,
            _ => Cursors.Arrow,
        };
    }

    private CanvasPointerCursorMode CurrentPointerCursorMode() =>
        CanvasPointerFeedback.Resolve(SingleFingerPanEnabled, _stylusInRange, SelectedTool?.Kind);

    private BitmapSource? LoadBitmap(string assetName, bool inverted)
    {
        var path = ResolveAssetPath?.Invoke(assetName);
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path)) return null;
        var key = $"{path}|{inverted}|{File.GetLastWriteTimeUtc(path).Ticks}";
        if (_bitmapCache.TryGetValue(key, out var cached)) return cached;
        try
        {
            var source = new BitmapImage();
            source.BeginInit(); source.CacheOption = BitmapCacheOption.OnLoad; source.UriSource = new Uri(path); source.EndInit(); source.Freeze();
            var result = ApplyImageInversion(source, inverted);
            _bitmapCache[key] = result;
            return result;
        }
        catch { return null; }
    }

    private BitmapSource? LoadAdaptivePdfBitmap(PageObject item, bool inverted, bool lightExport)
    {
        var preview = LoadBitmap(item.AssetName, inverted);
        if (lightExport || string.IsNullOrWhiteSpace(item.SourceAssetName)) return preview;
        var sourcePath = ResolveAssetPath?.Invoke(item.SourceAssetName);
        if (string.IsNullOrWhiteSpace(sourcePath) || !File.Exists(sourcePath)) return preview;

        var targetWidth = PdfRenderSizing.ForDisplay(item.Width, _viewport.Scale, VisualTreeHelper.GetDpi(this).DpiScaleX);
        var prefix = $"{sourcePath}|{Math.Max(1, item.SourcePageNumber ?? 1)}|{inverted}|{File.GetLastWriteTimeUtc(sourcePath).Ticks}|";
        var key = $"{prefix}{targetWidth}";
        if (_pdfBitmapCache.TryGetValue(key, out var exact)) return exact;
        if (!_pendingPdfRenders.Contains(key) && !_failedPdfRenders.Contains(key))
        {
            _pendingPdfRenders.Add(key);
            _ = RenderAdaptivePdfBitmapAsync(sourcePath, Math.Max(1, item.SourcePageNumber ?? 1), targetWidth, inverted, key, _pdfRenderGeneration);
        }
        return _pdfBitmapCache.FirstOrDefault(entry => entry.Key.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)).Value ?? preview;
    }

    private async Task RenderAdaptivePdfBitmapAsync(string sourcePath, int pageNumber, uint targetWidth, bool inverted, string key, int generation)
    {
        try
        {
            var rendered = await PdfImportService.RenderPageAsync(sourcePath, pageNumber, targetWidth).ConfigureAwait(false);
            var bitmap = BitmapFromBytes(rendered.PngBytes);
            bitmap = ApplyImageInversion(bitmap, inverted);
            await Dispatcher.InvokeAsync(() =>
            {
                if (generation != _pdfRenderGeneration) return;
                _pdfBitmapCache[key] = bitmap;
                InvalidateVisual();
            });
        }
        catch (Exception error)
        {
            System.Diagnostics.Trace.TraceWarning($"PDF display render failed: {sourcePath}, page {pageNumber}, width {targetWidth}: {error}");
            await Dispatcher.InvokeAsync(() =>
            {
                if (generation == _pdfRenderGeneration) _failedPdfRenders.Add(key);
            });
        }
        finally
        {
            await Dispatcher.InvokeAsync(() => _pendingPdfRenders.Remove(key));
        }
    }

    private static BitmapSource BitmapFromBytes(byte[] bytes)
    {
        using var stream = new MemoryStream(bytes, writable: false);
        var source = new BitmapImage();
        source.BeginInit();
        source.CacheOption = BitmapCacheOption.OnLoad;
        source.StreamSource = stream;
        source.EndInit();
        source.Freeze();
        return source;
    }

    private static BitmapSource ApplyImageInversion(BitmapSource source, bool inverted)
    {
        if (!inverted) return source;
        var converted = new FormatConvertedBitmap(source, PixelFormats.Bgra32, null, 0);
        var stride = converted.PixelWidth * 4;
        var pixels = new byte[stride * converted.PixelHeight];
        converted.CopyPixels(pixels, stride, 0);
        for (var i = 0; i < pixels.Length; i += 4)
        {
            pixels[i] = (byte)(27 + (255 - pixels[i]) * 228 / 255);
            pixels[i + 1] = (byte)(27 + (255 - pixels[i + 1]) * 228 / 255);
            pixels[i + 2] = (byte)(27 + (255 - pixels[i + 2]) * 228 / 255);
        }
        var bitmap = BitmapSource.Create(converted.PixelWidth, converted.PixelHeight, converted.DpiX, converted.DpiY, PixelFormats.Bgra32, null, pixels, stride);
        bitmap.Freeze();
        return bitmap;
    }

    private TextItem? HitText(Point viewPoint)
    {
        if (Document == null) return null;
        if (Document.Layers.Count > 0) return HitSourceContent(viewPoint) as TextItem;
        var doc = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        return Document.TextItems.LastOrDefault(value => new Rect(value.X, value.Y, value.Width, value.Height).Contains(doc));
    }

    private PageObject? HitObject(Point viewPoint)
    {
        if (Document == null) return null;
        if (Document.Layers.Count > 0) return HitSourceContent(viewPoint) as PageObject;
        var doc = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        return Document.Objects.LastOrDefault(value => new Rect(value.X, value.Y, value.Width, value.Height).Contains(doc));
    }

    private CanvasTag? HitTag(Point viewPoint)
    {
        if (Document == null) return null;
        var doc = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        var radius = 14 / Math.Max(0.01, _viewport.Scale);
        return Document.Tags.LastOrDefault(value => Distance(new Point(value.DocumentX, value.DocumentY), doc) <= radius);
    }

    private StrokeRecord? HitStroke(Point viewPoint)
    {
        if (Document == null) return null;
        if (Document.Layers.Count > 0) return HitSourceContent(viewPoint) as StrokeRecord;
        var doc = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        var radius = 10 / Math.Max(0.01, _viewport.Scale);
        return Document.Strokes.LastOrDefault(stroke => StrokeHitsCircle(stroke, doc, radius));
    }

    private object? HitSourceContent(Point viewPoint)
    {
        if (Document == null) return null;
        var point = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        var radius = 5 / Math.Max(0.01, _viewport.Scale);
        foreach (var item in PageScene.OrderedContent(Document, new Rect(point.X-radius,point.Y-radius,radius*2,radius*2)).Reverse())
        {
            if (item is StrokeRecord stroke && StrokeHitsCircle(stroke, point, radius)) return stroke;
            if (item is PageObject obj && !obj.IsPageBackground && new Rect(obj.X, obj.Y, obj.Width, obj.Height).Contains(point)) return obj;
            if (item is TextItem text && new Rect(text.X, text.Y, text.Width, text.Height).Contains(point)) return text;
        }
        return null;
    }

    private TextBox? EditorAt(Point viewPoint) =>
        EditorLayer.Children.OfType<TextBox>().LastOrDefault(editor =>
            editor.Tag is TextItem item && new Rect(item.X, item.Y, item.Width, item.Height)
                .Contains(_viewport.ViewToDocument(viewPoint.X, viewPoint.Y)));

    private bool HitSelectedContent(Point viewPoint)
    {
        var text = HitText(viewPoint);
        if (text != null && _selectedTextItems.Contains(text)) return true;
        var item = HitObject(viewPoint);
        if (item != null && _selectedObjects.Contains(item)) return true;
        var tag = HitTag(viewPoint);
        if (tag != null && _selectedTags.Contains(tag)) return true;
        var stroke = HitStroke(viewPoint);
        return stroke != null && _selectedStrokes.Contains(stroke);
    }

    private bool ActivateTextEditor(TextItem item)
    {
        if (!_selectedTextItems.Contains(item)) SelectOnlyText(item);
        var editor = EditorLayer.Children.OfType<TextBox>().FirstOrDefault(value => ReferenceEquals(value.Tag, item));
        if (editor == null) return false;
        editor.IsHitTestVisible = true;
        editor.Focus();
        editor.CaretIndex = editor.Text.Length;
        return true;
    }

    private Rect VisibleDocumentBounds()
    {
        var topLeft = _viewport.ViewToDocument(0, 0);
        var bottomRight = _viewport.ViewToDocument(ActualWidth, ActualHeight);
        return new Rect(topLeft, bottomRight);
    }

    private Rect CalculateDocumentExtent()
    {
        if (Document != null && Appearance.PaperKind == PaperKind.Infinite)
            return InfinitePageLayout.ExportBounds(Document);
        var extent = Appearance.PaperKind != PaperKind.Infinite
            ? new Rect(0, 0, Appearance.Width, Math.Max(1, Appearance.SheetCount) * Appearance.Height + Math.Max(0, Appearance.SheetCount - 1) * A4PageLayout.PageGap)
            : new Rect(0, 0, Appearance.InfiniteWidth, Appearance.InfiniteHeight);
        if (Document == null) return extent;
        foreach (var stroke in Document.Strokes) if (!stroke.Bounds.IsEmpty) extent = Rect.Union(extent, stroke.Bounds);
        foreach (var text in Document.TextItems) extent = Rect.Union(extent, new Rect(text.X, text.Y, text.Width, text.Height));
        foreach (var item in Document.Objects) extent = Rect.Union(extent, new Rect(item.X, item.Y, item.Width, item.Height));
        extent.Inflate(24, 24);
        return extent;
    }

    private void PushUndo()
    {
        if (Document == null) return;
        PushHistory(new UndoEntry(Json: Snapshot(Document)));
    }

    private void PushInkUndo(string strokeId) => PushHistory(new UndoEntry(AddedStrokeId: strokeId));

    private void PushHistory(UndoEntry entry)
    {
        _undo.Push(entry);
        while (_undo.Count > 80)
        {
            var keep = _undo.Take(80).Reverse().ToArray();
            _undo.Clear(); foreach (var item in keep) _undo.Push(item);
        }
        _redo.Clear();
    }

    private static string Snapshot(PageDocument document) => JsonSerializer.Serialize(document, SnapshotJson);

    private void RestoreSnapshot(UndoEntry entry)
    {
        if (entry.Json != null) { RestoreSnapshot(entry.Json); return; }
        if (Document == null) return;
        Document.Strokes.RemoveAll(stroke => stroke.Id == entry.AddedStrokeId);
        ClearSelection();
        MarkChanged(immediate: true);
        InvalidateVisual();
    }

    private void RestoreSnapshot(string json)
    {
        if (Document == null) return;
        var restored = JsonSerializer.Deserialize<PageDocument>(json, SnapshotJson);
        if (restored == null) return;
        Document.Strokes = restored.Strokes;
        Document.Tags = restored.Tags;
        Document.TextItems = restored.TextItems;
        Document.Objects = restored.Objects;
        Document.Layers = restored.Layers;
        Document.Viewport = restored.Viewport;
        _viewport.Restore(Document.Viewport);
        ClearSelection();
        RebuildTextEditors();
        MarkChanged(immediate: true);
        InvalidateVisual();
    }

    private void MarkChanged(bool immediate = false)
    {
        if (Document == null) return;
        GrowA4IfNeeded();
        InfinitePageLayout.EnsureCapacity(Appearance, Document);
        _viewport.CopyTo(Document.Viewport);
        if (immediate) { _saveTimer.Stop(); RaiseDocumentChanged(); }
        else { _saveTimer.Stop(); _saveTimer.Start(); }
    }

    private bool GrowA4IfNeeded()
    {
        if (Document == null || Appearance.PaperKind != PaperKind.A4) return false;
        var bottom = 0d;
        foreach (var stroke in Document.Strokes) if (!stroke.Bounds.IsEmpty) bottom = Math.Max(bottom, stroke.Bounds.Bottom);
        foreach (var tag in Document.Tags) bottom = Math.Max(bottom, tag.DocumentY + 18 * tag.Scale);
        foreach (var text in Document.TextItems) bottom = Math.Max(bottom, text.Y + text.Height);
        foreach (var item in Document.Objects) bottom = Math.Max(bottom, item.Y + item.Height);
        var visibleBottom = ActualHeight > 0 ? _viewport.ViewToDocument(0, ActualHeight).Y : 0;
        var required = A4PageLayout.RequiredSheetCount(bottom, visibleBottom, Appearance.SheetCount, Appearance.Height);
        if (required == Appearance.SheetCount) return false;
        Appearance.SheetCount = required;
        return true;
    }

    private void RaiseDocumentChanged() => DocumentChanged?.Invoke(this, EventArgs.Empty);

    private void ViewportMutated()
    {
        UpdateTextEditorPositions();
        if (Document != null) _viewport.CopyTo(Document.Viewport);
        ViewportChanged?.Invoke(this, EventArgs.Empty);
        if (GrowA4IfNeeded()) PageMetadataChanged?.Invoke(this, EventArgs.Empty);
        InvalidateVisual();
    }

    private void CommitViewport(PageDocument? document)
    {
        if (document != null) _viewport.CopyTo(document.Viewport);
    }

    private void ClearTransientState()
    {
        _pressureTravel.Reset();
        _straightenTimer.Stop(); _straighten.Cancel(); _explicitShape = false;
        CancelRightButtonLasso();
        _rightLassoHoldTimer.Stop();
        _stylusContextMenuTimer.Stop();
        AbandonTemporaryText();
        _directLongPressTimer.Stop();
        _directTouchDevice = null;
        _touchInsertSpaceActive = false;
        _insertSpaceUndoPushed = false;
        _directGestureMode = DirectGestureMode.None;
        _activeStroke = null; _isPanning = false; _isDragZooming = false; _isLassoing = false; _isTransforming = false; _isInsertingSpace = false;
        _stylusSelectionTransformActive = false; _stylusSelectionTransformMoved = false;
        _middleNavigationActive = false; _middleNavigationUsesStylus = false;
        EndPanoramaSpringLoadedTool();
        if (_temporaryPressureEraser) EndSpringLoadedTool?.Invoke(SpringLoadedToolKind.Eraser);
        _pressureCandidate = false; _pressureCandidateEraserTool = null; _temporaryPressureEraser = false; _activePressureEraserTool = null; _pressureTimer.Stop(); _eraserViewPoint = null;
        ClearSelection();
    }

    private void ClearSelection(bool keepLasso = false)
    {
        _selectedStrokes.Clear(); _selectedTags.Clear(); _selectedTextItems.Clear(); _selectedObjects.Clear(); _selectionBounds = null;
        if (!keepLasso) _lasso.Clear();
        UpdateTextEditorInteraction();
    }

    private bool HasContentSelection() =>
        _selectedStrokes.Count > 0 ||
        _selectedTags.Count > 0 ||
        _selectedTextItems.Count > 0 ||
        _selectedObjects.Count > 0;

    private static StrokeRecord CloneStroke(StrokeRecord source, float offset = 0) => new()
    {
        Id = IdFactory.New("stroke"),
        Kind = source.Kind,
        Color = source.Color,
        WidthMm = source.WidthMm,
        PressurePercent = source.PressurePercent,
        InkFlowPercent = source.InkFlowPercent,
        OpacityPercent = source.OpacityPercent,
        BrushProfile = source.BrushProfile,
        SourceBrush = source.SourceBrush,
        ColorHex = source.ColorHex,
        LayerId = source.LayerId,
        ZOrder = source.ZOrder,
        VectorPath = source.VectorPath,
        VectorBounds = source.VectorBounds?.ToArray(),
        VectorTransform = source.VectorTransform.Select((value, index) => index >= 4 ? value + offset : value).ToArray(),
        Points = source.Points.Select(point => new InkPoint(
            point.X + offset,
            point.Y + offset,
            point.Pressure,
            point.TiltX,
            point.TiltY,
            point.ElapsedMilliseconds)).ToList(),
    };

    private static CanvasTag CloneTag(CanvasTag source, float offset = 0) => new()
    {
        Id = IdFactory.New("tag"),
        Kind = source.Kind,
        DocumentX = source.DocumentX + offset,
        DocumentY = source.DocumentY + offset,
        Scale = source.Scale,
    };

    private static TextItem CloneText(TextItem source, float offset = 0) => new()
    {
        LayerId = source.LayerId,
        ZOrder = source.ZOrder,
        Id = IdFactory.New("text"),
        X = source.X + offset,
        Y = source.Y + offset,
        Width = source.Width,
        Height = source.Height,
        Text = source.Text,
        FontFamily = source.FontFamily,
        FontSize = source.FontSize,
        Bold = source.Bold,
        Italic = source.Italic,
        Underline = source.Underline,
        Strikethrough = source.Strikethrough,
        Subscript = source.Subscript,
        Color = source.Color,
        ForegroundHex = source.ForegroundHex,
        BackgroundHex = source.BackgroundHex,
        IndentLevel = source.IndentLevel,
        Alignment = source.Alignment,
        LineSpacing = source.LineSpacing,
        StyleName = source.StyleName,
    };

    private sealed record SelectionClipboardData(
        string NotebookId,
        string PageId,
        List<StrokeRecord> Strokes,
        List<CanvasTag> Tags,
        List<TextItem> TextItems,
        List<SelectionObjectClipboard> Objects);

    private sealed record SelectionObjectClipboard(PageObject Item, string? AssetPath, string? SourceAssetPath);

    private void SelectOnlyObject(PageObject item)
    {
        ClearSelection();
        if (item.IsPageBackground) return;
        _selectedObjects.Add(item);
        RecomputeSelectionBounds();
        InvalidateVisual();
    }

    private void SelectOnlyText(TextItem item)
    {
        ClearSelection();
        _selectedTextItems.Add(item);
        RecomputeSelectionBounds();
        InvalidateVisual();
    }

    private void SelectOnlyTag(CanvasTag tag)
    {
        ClearSelection();
        _selectedTags.Add(tag);
        RecomputeSelectionBounds();
        InvalidateVisual();
    }

    private void SelectOnlyStroke(StrokeRecord stroke)
    {
        ClearSelection();
        _selectedStrokes.Add(stroke);
        RecomputeSelectionBounds();
        InvalidateVisual();
    }

    private static bool HitSelectionHandle(Point point, Rect bounds, out int handle)
    {
        var points = SelectionGeometry.SelectionHandles(bounds);
        var bestDistance = double.PositiveInfinity;
        var bestIndex = -1;
        for (var index = 0; index < points.Count; index++)
        {
            var distance = Distance(point, points[index]);
            if (distance <= 12 && distance < bestDistance) { bestDistance = distance; bestIndex = index; }
        }
        if (bestIndex >= 0) { handle = bestIndex; return true; }
        handle = bounds.Contains(point) ? -1 : int.MinValue;
        return false;
    }

    private static Point OppositeHandlePoint(Rect bounds, int handle) => handle switch
    {
        0 => bounds.BottomRight,
        1 => new Point(bounds.Left + bounds.Width / 2, bounds.Bottom),
        2 => bounds.BottomLeft,
        3 => new Point(bounds.Left, bounds.Top + bounds.Height / 2),
        4 => bounds.TopLeft,
        5 => new Point(bounds.Left + bounds.Width / 2, bounds.Top),
        6 => bounds.TopRight,
        7 => new Point(bounds.Right, bounds.Top + bounds.Height / 2),
        _ => bounds.TopLeft,
    };

    private static double Distance(Point left, Point right)
    {
        var dx = left.X - right.X; var dy = left.Y - right.Y; return Math.Sqrt(dx * dx + dy * dy);
    }

    private static float Tilt(StylusPoint point, StylusPointProperty property)
    {
        try
        {
            return point.Description.HasProperty(property) ? point.GetPropertyValue(property) : 0;
        }
        catch (ArgumentException)
        {
            return 0;
        }
    }

    private static Brush ParseBrush(string value, Color fallback)
    {
        try { return new SolidColorBrush((Color)ColorConverter.ConvertFromString(value)); }
        catch { return new SolidColorBrush(fallback); }
    }

    private static SolidColorBrush TextBrush(InkPalette palette, bool darkPage, bool lightExport)
    {
        var color = palette switch
        {
            InkPalette.Blue => Color.FromRgb(70, 153, 255),
            InkPalette.Red => Color.FromRgb(232, 49, 57),
            _ => lightExport || !darkPage ? Colors.Black : Color.FromRgb(239, 239, 239),
        };
        return new SolidColorBrush(color);
    }

    private static Brush TextItemBrush(TextItem item, bool darkPage, bool lightExport)
    {
        if (!string.IsNullOrWhiteSpace(item.ForegroundHex) && !(lightExport && item.ForegroundHex.Equals("#F4F4F4", StringComparison.OrdinalIgnoreCase)))
            return ParseBrush(item.ForegroundHex, TextBrush(item.Color, darkPage, lightExport));
        return TextBrush(item.Color, darkPage, lightExport);
    }

    private static Brush ParseBrush(string? value, Brush fallback)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Equals("Transparent", StringComparison.OrdinalIgnoreCase)) return value?.Equals("Transparent", StringComparison.OrdinalIgnoreCase) == true ? Brushes.Transparent : fallback;
        try
        {
            var converted = ColorConverter.ConvertFromString(value);
            return converted is Color color ? new SolidColorBrush(color) : fallback;
        }
        catch { return fallback; }
    }

    private static TextAlignment ParseAlignment(string? value) => value switch
    {
        "Center" => TextAlignment.Center,
        "Right" => TextAlignment.Right,
        _ => TextAlignment.Left,
    };

    private static TextDecorationCollection? DecorationsFor(TextItem item)
    {
        if (!item.Underline && !item.Strikethrough) return null;
        var result = new TextDecorationCollection();
        if (item.Underline) result.Add(TextDecorations.Underline[0]);
        if (item.Strikethrough) result.Add(TextDecorations.Strikethrough[0]);
        return result;
    }

    private void DrawText(DrawingContext dc, string text, Point location, double size, Brush brush, bool bold = false, bool underline = false, bool italic = false, double maxWidth = double.PositiveInfinity)
    {
        var formatted = new FormattedText(text ?? string.Empty, CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
            new Typeface(new FontFamily("Segoe UI Variable, Microsoft YaHei UI"), italic ? FontStyles.Italic : FontStyles.Normal, bold ? FontWeights.Bold : FontWeights.Normal, FontStretches.Normal),
            size, brush, VisualTreeHelper.GetDpi(this).PixelsPerDip)
        {
            MaxTextWidth = double.IsFinite(maxWidth) ? Math.Max(1, maxWidth) : 100_000,
            Trimming = TextTrimming.CharacterEllipsis,
        };
        if (underline) formatted.SetTextDecorations(TextDecorations.Underline);
        dc.DrawText(formatted, location);
    }

    private static void DrawStar(DrawingContext dc, Point center, double radius, Brush brush)
    {
        var geometry = new StreamGeometry();
        using (var context = geometry.Open())
        {
            for (var i = 0; i < 10; i++)
            {
                var r = i % 2 == 0 ? radius : radius * 0.45;
                var angle = -Math.PI / 2 + i * Math.PI / 5;
                var point = new Point(center.X + Math.Cos(angle) * r, center.Y + Math.Sin(angle) * r);
                if (i == 0) context.BeginFigure(point, true, true); else context.LineTo(point, true, false);
            }
        }
        geometry.Freeze(); dc.DrawGeometry(brush, null, geometry);
    }
}

internal static class StylusPointExtensions
{
    public static Point ToPoint(this StylusPoint point) => new(point.X, point.Y);
}
