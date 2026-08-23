using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Controls;

public enum CanvasRenderLayer { Background, Inserts, InkAndText, Composite }

public sealed class PageObjectEventArgs(PageObject value) : EventArgs
{
    public PageObject Value { get; } = value;
}

public partial class NoteCanvas : UserControl
{
    private static readonly JsonSerializerOptions SnapshotJson = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    private const double UnitsPerMillimeter = 96d / 25.4d;

    private readonly ViewportState _viewport = new();
    private readonly Stack<string> _undo = new();
    private readonly Stack<string> _redo = new();
    private readonly List<Point> _lasso = new();
    private readonly List<StrokeRecord> _selectedStrokes = new();
    private readonly List<CanvasTag> _selectedTags = new();
    private readonly List<TextItem> _selectedTextItems = new();
    private readonly List<PageObject> _selectedObjects = new();
    private readonly Dictionary<string, BitmapSource> _bitmapCache = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<int> _activeTouchDevices = new();
    private readonly DispatcherTimer _saveTimer;
    private readonly DispatcherTimer _pressureTimer;
    private readonly Stopwatch _strokeClock = new();

    private StrokeRecord? _activeStroke;
    private Rect? _selectionBounds;
    private Point _lastPointer;
    private Point _transformPoint;
    private Point _lassoOrigin;
    private bool _isPanning;
    private bool _isDragZooming;
    private bool _isLassoing;
    private bool _isTransforming;
    private int _resizeHandle = -1;
    private bool _isInsertingSpace;
    private double _insertStartY;
    private double _insertCurrentY;
    private Point? _eraserViewPoint;
    private Point? _hoverViewPoint;
    private bool _stylusActive;
    private bool _stylusEraserActive;
    private TouchDevice? _touchLassoDevice;
    private long _ignoreMouseUntil;
    private bool _pressureEraseArmed;
    private bool _pressureCandidate;
    private bool _temporaryPressureEraser;
    private Point _pressureStartView;
    private DateTime _pressureStartedAt;
    private bool _suppressEditorChange;

    public static readonly DependencyProperty DocumentProperty = DependencyProperty.Register(
        nameof(Document), typeof(PageDocument), typeof(NoteCanvas), new PropertyMetadata(null, OnDocumentChanged));
    public static readonly DependencyProperty AppearanceProperty = DependencyProperty.Register(
        nameof(Appearance), typeof(PageAppearance), typeof(NoteCanvas), new FrameworkPropertyMetadata(new PageAppearance(), FrameworkPropertyMetadataOptions.AffectsRender, OnAppearanceChanged));
    public static readonly DependencyProperty PageTitleProperty = DependencyProperty.Register(
        nameof(PageTitle), typeof(string), typeof(NoteCanvas), new FrameworkPropertyMetadata(string.Empty, FrameworkPropertyMetadataOptions.AffectsRender));
    public static readonly DependencyProperty SelectedToolProperty = DependencyProperty.Register(
        nameof(SelectedTool), typeof(ToolPreset), typeof(NoteCanvas), new PropertyMetadata(null, OnSelectedToolChanged));
    public static readonly DependencyProperty SingleFingerPanEnabledProperty = DependencyProperty.Register(
        nameof(SingleFingerPanEnabled), typeof(bool), typeof(NoteCanvas), new PropertyMetadata(false));
    public static readonly DependencyProperty TextInsertionArmedProperty = DependencyProperty.Register(
        nameof(TextInsertionArmed), typeof(bool), typeof(NoteCanvas), new PropertyMetadata(false, OnTextInsertionChanged));
    public static readonly DependencyProperty InsertSpaceModeEnabledProperty = DependencyProperty.Register(
        nameof(InsertSpaceModeEnabled), typeof(bool), typeof(NoteCanvas), new PropertyMetadata(false));
    public static readonly DependencyProperty PendingTagKindProperty = DependencyProperty.Register(
        nameof(PendingTagKind), typeof(CanvasTagKind?), typeof(NoteCanvas), new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.BindsTwoWayByDefault));
    public static readonly DependencyProperty ZoomLockedProperty = DependencyProperty.Register(
        nameof(ZoomLocked), typeof(bool), typeof(NoteCanvas), new PropertyMetadata(false));
    public static readonly DependencyProperty PredictionEnabledProperty = DependencyProperty.Register(
        nameof(PredictionEnabled), typeof(bool), typeof(NoteCanvas), new PropertyMetadata(true));

    public PageDocument? Document { get => (PageDocument?)GetValue(DocumentProperty); set => SetValue(DocumentProperty, value); }
    public PageAppearance Appearance { get => (PageAppearance)GetValue(AppearanceProperty); set => SetValue(AppearanceProperty, value); }
    public string PageTitle { get => (string)GetValue(PageTitleProperty); set => SetValue(PageTitleProperty, value); }
    public ToolPreset? SelectedTool { get => (ToolPreset?)GetValue(SelectedToolProperty); set => SetValue(SelectedToolProperty, value); }
    public bool SingleFingerPanEnabled { get => (bool)GetValue(SingleFingerPanEnabledProperty); set => SetValue(SingleFingerPanEnabledProperty, value); }
    public bool TextInsertionArmed { get => (bool)GetValue(TextInsertionArmedProperty); set => SetValue(TextInsertionArmedProperty, value); }
    public bool InsertSpaceModeEnabled { get => (bool)GetValue(InsertSpaceModeEnabledProperty); set => SetValue(InsertSpaceModeEnabledProperty, value); }
    public CanvasTagKind? PendingTagKind { get => (CanvasTagKind?)GetValue(PendingTagKindProperty); set => SetValue(PendingTagKindProperty, value); }
    public bool ZoomLocked { get => (bool)GetValue(ZoomLockedProperty); set => SetValue(ZoomLockedProperty, value); }
    public bool PredictionEnabled { get => (bool)GetValue(PredictionEnabledProperty); set => SetValue(PredictionEnabledProperty, value); }
    public ViewportState Viewport => _viewport;
    public Func<string, string>? ResolveAssetPath { get; set; }

    public event EventHandler? DocumentChanged;
    public event EventHandler? ViewportChanged;
    public event EventHandler<PageObjectEventArgs>? ObjectActivated;
    public event EventHandler<PageObjectEventArgs>? ObjectContextRequested;

    public NoteCanvas()
    {
        InitializeComponent();
        Focusable = true;
        _saveTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(350) };
        _saveTimer.Tick += (_, _) => { _saveTimer.Stop(); RaiseDocumentChanged(); };
        _pressureTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
        _pressureTimer.Tick += (_, _) => CheckPressureEraseActivation();
        AddHandler(Stylus.StylusDownEvent, new StylusDownEventHandler(HandleStylusDown), handledEventsToo: true);
        AddHandler(Stylus.StylusMoveEvent, new StylusEventHandler(HandleStylusMove), handledEventsToo: true);
        AddHandler(Stylus.StylusUpEvent, new StylusEventHandler(HandleStylusUp), handledEventsToo: true);
    }

    private static void OnDocumentChanged(DependencyObject source, DependencyPropertyChangedEventArgs args)
    {
        var canvas = (NoteCanvas)source;
        canvas.CommitViewport(args.OldValue as PageDocument);
        canvas._undo.Clear();
        canvas._redo.Clear();
        canvas.ClearTransientState();
        canvas._bitmapCache.Clear();
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

    private static void OnTextInsertionChanged(DependencyObject source, DependencyPropertyChangedEventArgs args)
    {
        var canvas = (NoteCanvas)source;
        canvas.UpdatePointerCursor();
    }

    private static void OnSelectedToolChanged(DependencyObject source, DependencyPropertyChangedEventArgs args)
    {
        var canvas = (NoteCanvas)source;
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

    public void DeleteSelection()
    {
        if (Document == null || (_selectedStrokes.Count == 0 && _selectedTags.Count == 0 && _selectedTextItems.Count == 0 && _selectedObjects.Count == 0)) return;
        PushUndo();
        foreach (var stroke in _selectedStrokes) Document.Strokes.Remove(stroke);
        foreach (var tag in _selectedTags) Document.Tags.Remove(tag);
        foreach (var text in _selectedTextItems) Document.TextItems.Remove(text);
        foreach (var item in _selectedObjects) Document.Objects.Remove(item);
        ClearSelection();
        RebuildTextEditors();
        MarkChanged(immediate: true);
        InvalidateVisual();
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

    public bool MoveObjectToFront(PageObject item) => ReorderObject(item, Document?.Objects.Count - 1 ?? 0);
    public bool MoveObjectToBack(PageObject item)
    {
        if (Document == null) return false;
        var firstMovable = Document.Objects.FindIndex(candidate => !candidate.IsPageBackground);
        return ReorderObject(item, Math.Max(0, firstMovable));
    }
    public bool MoveObjectForward(PageObject item) => ReorderObject(item, (Document?.Objects.IndexOf(item) ?? -1) + 1);
    public bool MoveObjectBackward(PageObject item) => ReorderObject(item, (Document?.Objects.IndexOf(item) ?? -1) - 1);

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
            DrawDocument(dc, layer, lightExport: true, extent);
            dc.Pop();
            dc.Pop();
        }
        var bitmap = new RenderTargetBitmap(pixelWidth, pixelHeight, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        bitmap.Freeze();
        return bitmap;
    }

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
        if (Environment.TickCount64 < _ignoreMouseUntil || Document == null) return;
        if (e.ChangedButton == MouseButton.Right)
        {
            var item = HitObject(e.GetPosition(this));
            if (item == null || item.IsPageBackground) return;
            SelectOnlyObject(item);
            ObjectContextRequested?.Invoke(this, new PageObjectEventArgs(item));
            e.Handled = true;
            return;
        }
        if (e.ChangedButton is not MouseButton.Left and not MouseButton.Middle) return;
        Focus();
        var point = e.GetPosition(this);
        if (e.ClickCount == 2 && e.ChangedButton == MouseButton.Left && HandleDoubleClick(point)) { e.Handled = true; return; }
        BeginPointer(point, pressure: 0.65f, tiltX: 0, tiltY: 0, isStylus: false, forcePan: e.MiddleButton == MouseButtonState.Pressed || Keyboard.IsKeyDown(Key.Space), forceErase: false);
        e.Handled = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (Environment.TickCount64 < _ignoreMouseUntil || Document == null) return;
        _hoverViewPoint = e.GetPosition(this);
        UpdatePointerCursor();
        if (e.LeftButton == MouseButtonState.Pressed || e.MiddleButton == MouseButtonState.Pressed || IsMouseCaptured)
        {
            ContinuePointer(_hoverViewPoint.Value, 0.65f, 0, 0, isStylus: false);
            e.Handled = true;
        }
        InvalidateVisual();
    }

    protected override void OnMouseEnter(MouseEventArgs e)
    {
        base.OnMouseEnter(e);
        _hoverViewPoint = e.GetPosition(this);
        UpdatePointerCursor();
        InvalidateVisual();
    }

    protected override void OnMouseLeave(MouseEventArgs e)
    {
        base.OnMouseLeave(e);
        if (IsMouseCaptured || _stylusActive) return;
        _hoverViewPoint = null;
        Cursor = Cursors.Arrow;
        InvalidateVisual();
    }

    protected override void OnMouseUp(MouseButtonEventArgs e)
    {
        base.OnMouseUp(e);
        if (Environment.TickCount64 < _ignoreMouseUntil || Document == null) return;
        EndPointer(isStylus: false);
        e.Handled = true;
    }

    private void HandleStylusDown(object sender, StylusDownEventArgs e)
    {
        if (Document == null || e.StylusDevice.TabletDevice.Type == TabletDeviceType.Touch) return;
        _stylusActive = true;
        _stylusEraserActive = e.StylusDevice.Inverted;
        _ignoreMouseUntil = Environment.TickCount64 + 250;
        var points = e.GetStylusPoints(this);
        var point = points.Count > 0 ? points[^1] : default;
        BeginPointer(point.ToPoint(), point.PressureFactor, Tilt(point, StylusPointProperties.XTiltOrientation), Tilt(point, StylusPointProperties.YTiltOrientation), isStylus: true, forcePan: false, forceErase: _stylusEraserActive);
        e.Handled = true;
    }

    private void HandleStylusMove(object sender, StylusEventArgs e)
    {
        if (Document == null || e.StylusDevice.TabletDevice.Type == TabletDeviceType.Touch) return;
        var points = e.GetStylusPoints(this);
        if (points.Count > 0)
        {
            _hoverViewPoint = points[^1].ToPoint();
            UpdatePointerCursor();
        }
        if (!_stylusActive)
        {
            InvalidateVisual();
            return;
        }
        foreach (var point in points)
            ContinuePointer(point.ToPoint(), point.PressureFactor, Tilt(point, StylusPointProperties.XTiltOrientation), Tilt(point, StylusPointProperties.YTiltOrientation), isStylus: true);
        e.Handled = true;
    }

    protected override void OnStylusLeave(StylusEventArgs e)
    {
        base.OnStylusLeave(e);
        if (_stylusActive) return;
        _hoverViewPoint = null;
        UpdatePointerCursor();
        InvalidateVisual();
    }

    private void HandleStylusUp(object sender, StylusEventArgs e)
    {
        if (!_stylusActive) return;
        EndPointer(isStylus: true);
        _stylusActive = false;
        _stylusEraserActive = false;
        _ignoreMouseUntil = Environment.TickCount64 + 250;
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
        if (_activeTouchDevices.Count >= 2 && _touchLassoDevice != null)
        {
            _isLassoing = false;
            _lasso.Clear();
            ReleaseTouchCapture(_touchLassoDevice);
            _touchLassoDevice = null;
            InvalidateVisual();
            return;
        }
        if (
            _activeTouchDevices.Count == 1 &&
            !TextInsertionArmed &&
            !SingleFingerPanEnabled &&
            SelectedTool?.Kind == DrawingToolKind.Lasso
        ) {
            BeginLasso(_viewport.ViewToDocument(e.GetTouchPoint(this).Position.X, e.GetTouchPoint(this).Position.Y));
            _touchLassoDevice = e.TouchDevice;
            CaptureTouch(e.TouchDevice);
            e.Handled = true;
        }
    }

    protected override void OnTouchMove(TouchEventArgs e)
    {
        base.OnTouchMove(e);
        if (_touchLassoDevice?.Id != e.TouchDevice.Id || !_isLassoing || _activeTouchDevices.Count != 1) return;
        var point = e.GetTouchPoint(this).Position;
        ExtendLasso(_viewport.ViewToDocument(point.X, point.Y));
        InvalidateVisual();
        e.Handled = true;
    }

    protected override void OnTouchUp(TouchEventArgs e)
    {
        var finishingLasso = _touchLassoDevice?.Id == e.TouchDevice.Id && _isLassoing;
        _activeTouchDevices.Remove(e.TouchDevice.Id);
        if (finishingLasso)
        {
            _isLassoing = false;
            _touchLassoDevice = null;
            ReleaseTouchCapture(e.TouchDevice);
            ComputeLassoSelection();
            e.Handled = true;
        }
        base.OnTouchUp(e);
    }

    protected override void OnTouchLeave(TouchEventArgs e)
    {
        if (_touchLassoDevice?.Id == e.TouchDevice.Id && e.TouchDevice.Captured == null)
        {
            _touchLassoDevice = null;
            _isLassoing = false;
            _lasso.Clear();
            InvalidateVisual();
        }
        if (e.TouchDevice.Captured == null) _activeTouchDevices.Remove(e.TouchDevice.Id);
        base.OnTouchLeave(e);
    }

    protected override void OnManipulationDelta(ManipulationDeltaEventArgs e)
    {
        base.OnManipulationDelta(e);
        if (Document == null || _activeStroke != null) return;
        if (!SingleFingerPanEnabled && _activeTouchDevices.Count < 2)
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

    private void BeginPointer(Point viewPoint, float pressure, float tiltX, float tiltY, bool isStylus, bool forcePan, bool forceErase)
    {
        if (Document == null) return;
        _lastPointer = viewPoint;
        var docPoint = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);

        if (forcePan || SingleFingerPanEnabled)
        {
            _isDragZooming = Keyboard.Modifiers.HasFlag(ModifierKeys.Control) && !ZoomLocked;
            _isPanning = !_isDragZooming;
            CaptureMouse();
            return;
        }

        if (_selectionBounds.HasValue)
        {
            var viewBounds = _viewport.DocumentToView(_selectionBounds.Value);
            if (HitSelectionHandle(viewPoint, viewBounds, out var handle) || viewBounds.Contains(viewPoint))
            {
                PushUndo();
                _isTransforming = true;
                _resizeHandle = handle;
                _transformPoint = docPoint;
                CaptureMouse();
                return;
            }
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

        if (InsertSpaceModeEnabled)
        {
            PushUndo();
            _isInsertingSpace = true;
            _insertStartY = _insertCurrentY = docPoint.Y;
            CaptureMouse();
            return;
        }

        if (TextInsertionArmed && !isStylus)
        {
            AddTextItem(docPoint);
            return;
        }

        var tool = SelectedTool ?? ToolShelf.Defaults().First();
        // Mouse remains a pointer device even while a pen/eraser preset is selected.
        // It may lasso or pan, but only a real stylus can author or erase ink.
        if (!isStylus && tool.Kind is DrawingToolKind.Pen or DrawingToolKind.Highlighter or DrawingToolKind.Eraser)
        {
            return;
        }

        switch (forceErase ? DrawingToolKind.Eraser : tool.Kind)
        {
            case DrawingToolKind.Pen:
            case DrawingToolKind.Highlighter:
                PushUndo();
                _strokeClock.Restart();
                _activeStroke = new StrokeRecord(IdFactory.New("stroke"), tool.Kind, tool.Color, tool.WidthMm, tool.OpacityPercent)
                {
                    PressurePercent = tool.PressurePercent,
                    InkFlowPercent = tool.InkFlowPercent,
                };
                AddStrokePoint(_activeStroke, docPoint, pressure, tiltX, tiltY);
                Document.Strokes.Add(_activeStroke);
                CaptureMouse();
                if (isStylus && tool.Kind == DrawingToolKind.Pen && tool.PressureEraseEnabled && _pressureEraseArmed && pressure * 100 >= tool.PressureEraseThresholdPercent)
                {
                    _pressureCandidate = true;
                    _pressureStartView = viewPoint;
                    _pressureStartedAt = DateTime.UtcNow;
                    _pressureTimer.Start();
                }
                break;
            case DrawingToolKind.Eraser:
                PushUndo();
                _eraserViewPoint = viewPoint;
                PerformErase(docPoint, (forceErase ? Math.Max(2.5f, tool.EraserRadiusMm) : tool.EraserRadiusMm) * UnitsPerMillimeter);
                CaptureMouse();
                break;
            case DrawingToolKind.Lasso:
                BeginLasso(docPoint);
                CaptureMouse();
                break;
        }
        InvalidateVisual();
    }

    private void ContinuePointer(Point viewPoint, float pressure, float tiltX, float tiltY, bool isStylus)
    {
        if (Document == null) return;
        var docPoint = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        if (_isDragZooming)
        {
            var factor = (float)Math.Exp((_lastPointer.Y - viewPoint.Y) / 240d);
            _viewport.ZoomAt((float)_lastPointer.X, (float)_lastPointer.Y, factor);
            _lastPointer = viewPoint;
            UpdateTextEditorPositions();
            InvalidateVisual();
            return;
        }
        if (_isPanning)
        {
            _viewport.Pan((float)(viewPoint.X - _lastPointer.X), (float)(viewPoint.Y - _lastPointer.Y));
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
                TranslateSelection(docPoint.X - _transformPoint.X, docPoint.Y - _transformPoint.Y);
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
            PerformErase(docPoint, 4.5 * UnitsPerMillimeter);
            InvalidateVisual();
            return;
        }
        if (_activeStroke != null)
        {
            if (_pressureCandidate && Distance(viewPoint, _pressureStartView) > 2)
            {
                _pressureStartedAt = DateTime.UtcNow;
                _pressureStartView = viewPoint;
            }
            AddStrokePoint(_activeStroke, docPoint, pressure, tiltX, tiltY);
            InvalidateVisual();
            return;
        }
        if (_eraserViewPoint.HasValue && (SelectedTool?.Kind == DrawingToolKind.Eraser || _stylusEraserActive))
        {
            _eraserViewPoint = viewPoint;
            PerformErase(docPoint, Math.Max(2.5f, SelectedTool?.EraserRadiusMm ?? 4.5f) * UnitsPerMillimeter);
            InvalidateVisual();
        }
    }

    private void EndPointer(bool isStylus)
    {
        if (Document == null) return;
        if (IsMouseCaptured) ReleaseMouseCapture();
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
        }
        if (_isTransforming)
        {
            _isTransforming = false;
            RecomputeSelectionBounds();
            changed = true;
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
            }
            _activeStroke = null;
            _pressureEraseArmed = true;
            changed = true;
        }
        if (_eraserViewPoint.HasValue) changed = true;
        _eraserViewPoint = null;
        _pressureCandidate = false;
        _temporaryPressureEraser = false;
        _pressureTimer.Stop();
        if (changed) MarkChanged(immediate: true);
        InvalidateVisual();
    }

    private void CheckPressureEraseActivation()
    {
        var tool = SelectedTool;
        if (!_pressureCandidate || _activeStroke == null || tool == null) return;
        if ((DateTime.UtcNow - _pressureStartedAt).TotalMilliseconds < tool.PressureEraseDwellMilliseconds) return;
        Document?.Strokes.Remove(_activeStroke);
        _activeStroke = null;
        _pressureCandidate = false;
        _temporaryPressureEraser = true;
        _eraserViewPoint = _pressureStartView;
        InvalidateVisual();
    }

    private void AddStrokePoint(StrokeRecord stroke, Point documentPoint, float pressure, float tiltX, float tiltY)
    {
        if (stroke.Points.Count > 0)
        {
            var previous = stroke.Points[^1];
            if (Math.Abs(previous.X - documentPoint.X) < 0.01 && Math.Abs(previous.Y - documentPoint.Y) < 0.01) return;
        }
        stroke.Points.Add(new InkPoint((float)documentPoint.X, (float)documentPoint.Y, pressure, tiltX, tiltY, _strokeClock.ElapsedMilliseconds));
    }

    private void PerformErase(Point point, double radius)
    {
        if (Document == null) return;
        Document.Strokes.RemoveAll(stroke => StrokeHitsCircle(stroke, point, radius));
        Document.Tags.RemoveAll(tag => Distance(new Point(tag.DocumentX, tag.DocumentY), point) <= radius + 14);
        ClearSelection();
    }

    private static bool StrokeHitsCircle(StrokeRecord stroke, Point point, double radius)
    {
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
        _selectedStrokes.AddRange(Document.Strokes.Where(stroke => stroke.Points.Any(point => SelectionGeometry.PointInPolygon(point.X, point.Y, _lasso))));
        _selectedTags.AddRange(Document.Tags.Where(tag => SelectionGeometry.PointInPolygon(tag.DocumentX, tag.DocumentY, _lasso)));
        _selectedTextItems.AddRange(Document.TextItems.Where(text => SelectionGeometry.PolygonIntersectsRect(_lasso, new Rect(text.X, text.Y, text.Width, text.Height))));
        _selectedObjects.AddRange(Document.Objects.Where(item => !item.IsPageBackground && SelectionGeometry.PolygonIntersectsRect(_lasso, new Rect(item.X, item.Y, item.Width, item.Height))));
        _lasso.Clear();
        RecomputeSelectionBounds();
    }

    private void BeginLasso(Point documentPoint)
    {
        ClearSelection();
        _isLassoing = true;
        _lassoOrigin = documentPoint;
        _lasso.Add(documentPoint);
    }

    private void ExtendLasso(Point documentPoint)
    {
        if (SelectedTool?.LassoShape != LassoShape.Rectangle)
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
        foreach (var stroke in _selectedStrokes) foreach (var point in stroke.Points) { point.X += (float)dx; point.Y += (float)dy; }
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
            foreach (var point in stroke.Points)
            {
                var scaled = SelectionGeometry.Scale(point.X, point.Y, anchor.X, anchor.Y, factorX, factorY);
                point.X = (float)scaled.X; point.Y = (float)scaled.Y;
            }
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
        foreach (var stroke in Document.Strokes.Where(item => item.Bounds.Top >= startY)) foreach (var point in stroke.Points) point.Y += deltaY;
        foreach (var tag in Document.Tags.Where(item => item.DocumentY >= startY)) tag.DocumentY += deltaY;
        foreach (var text in Document.TextItems.Where(item => item.Y >= startY)) text.Y += deltaY;
        foreach (var item in Document.Objects.Where(item => item.Y >= startY)) item.Y += deltaY;
        UpdateTextEditorPositions();
    }

    private void AddTextItem(Point documentPoint)
    {
        if (Document == null) return;
        PushUndo();
        var item = new TextItem { X = (float)documentPoint.X, Y = (float)documentPoint.Y };
        Document.TextItems.Add(item);
        var editor = CreateTextEditor(item);
        EditorLayer.Children.Add(editor);
        PositionEditor(editor, item);
        editor.Focus();
        MarkChanged();
    }

    private void RebuildTextEditors()
    {
        _suppressEditorChange = true;
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
        editor.PreviewMouseDoubleClick += (_, e) =>
        {
            if (_selectedTextItems.Count == 1 && ReferenceEquals(_selectedTextItems[0], item)) ActivateTextEditor(item);
            else SelectOnlyText(item);
            e.Handled = true;
        };
        editor.TextChanged += (_, _) =>
        {
            if (_suppressEditorChange) return;
            item.Text = editor.Text;
            item.Height = (float)Math.Max(32, editor.ActualHeight);
            MarkChanged();
        };
        editor.LostKeyboardFocus += (_, _) =>
        {
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
            editor.IsHitTestVisible = editor.Tag is not TextItem item || !_selectedTextItems.Contains(item);
            editor.Cursor = Cursors.IBeam;
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
        dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(10, 10, 10)), null, new Rect(0, 0, ActualWidth, ActualHeight));
        if (Document == null)
        {
            DrawText(dc, "选择或新建一个页面", new Point(48, 48), 18, new SolidColorBrush(Color.FromRgb(185, 185, 185)));
            return;
        }
        dc.PushTransform(new MatrixTransform(_viewport.DocumentToViewMatrix()));
        DrawDocument(dc, CanvasRenderLayer.Composite, lightExport: false, VisibleDocumentBounds());
        dc.Pop();
        DrawOverlays(dc);
    }

    private void DrawDocument(DrawingContext dc, CanvasRenderLayer layer, bool lightExport, Rect extent)
    {
        if (Document == null) return;
        if (layer is CanvasRenderLayer.Background or CanvasRenderLayer.Composite) DrawPaper(dc, lightExport, extent);
        if (layer is CanvasRenderLayer.Inserts or CanvasRenderLayer.Composite) DrawObjects(dc, lightExport);
        if (layer is CanvasRenderLayer.InkAndText or CanvasRenderLayer.Composite)
        {
            DrawStrokes(dc, lightExport);
            DrawTags(dc, lightExport);
            if (lightExport) DrawTextItems(dc, lightExport);
        }
    }

    private void DrawPaper(DrawingContext dc, bool lightExport, Rect extent)
    {
        var paper = lightExport ? Brushes.White : ParseBrush(Appearance.PageColor, Appearance.Theme == PageTheme.Dark ? Color.FromRgb(27, 27, 27) : Color.FromRgb(250, 250, 250));
        if (Appearance.PaperKind == PaperKind.Infinite)
        {
            dc.DrawRectangle(paper, null, extent);
            DrawPattern(dc, extent, lightExport);
        }
        else
        {
            var sheets = Math.Max(1, Appearance.SheetCount);
            for (var i = 0; i < sheets; i++)
            {
                var rect = new Rect(0, i * (Appearance.Height + 32), Appearance.Width, Appearance.Height);
                dc.DrawRectangle(paper, new Pen(new SolidColorBrush(Color.FromRgb(61, 61, 61)), 1 / Math.Max(0.1, _viewport.Scale)), rect);
                DrawPattern(dc, rect, lightExport);
            }
        }
        if (Appearance.ShowTitle && !string.IsNullOrWhiteSpace(PageTitle))
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

    private void DrawObjects(DrawingContext dc, bool lightExport)
    {
        if (Document == null) return;
        foreach (var item in Document.Objects)
        {
            var rect = new Rect(item.X, item.Y, item.Width, item.Height);
            var border = new Pen(new SolidColorBrush(lightExport ? Color.FromRgb(180, 180, 180) : Color.FromRgb(87, 87, 87)), 1);
            switch (item.Kind)
            {
                case PageObjectKind.Image:
                case PageObjectKind.PdfPrintout:
                case PageObjectKind.Sticker:
                    var bitmap = LoadBitmap(item.AssetName, item.ViewInverted && !lightExport);
                    if (bitmap != null) DrawTransformedImage(dc, bitmap, rect, item);
                    else { dc.DrawRectangle(lightExport ? Brushes.WhiteSmoke : new SolidColorBrush(Color.FromRgb(31, 31, 31)), border, rect); DrawText(dc, $"图片不可用 · {item.DisplayName}", new Point(rect.X + 12, rect.Y + 12), 12, lightExport ? Brushes.Black : Brushes.White); }
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

    private void DrawStrokes(DrawingContext dc, bool lightExport)
    {
        if (Document == null) return;
        foreach (var stroke in Document.Strokes)
        {
            if (stroke.Points.Count == 0) continue;
            var brush = TextBrush(stroke.Color, Appearance.Theme == PageTheme.Dark, lightExport);
            if (stroke.Kind == DrawingToolKind.Highlighter)
            {
                var color = ((SolidColorBrush)brush).Color;
                var alpha = (byte)Math.Clamp(255 * stroke.OpacityPercent / 100, 1, 255);
                var pen = new Pen(new SolidColorBrush(Color.FromArgb(alpha, color.R, color.G, color.B)), stroke.WidthMm * UnitsPerMillimeter)
                { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, LineJoin = PenLineJoin.Round };
                DrawPolyline(dc, stroke.Points, pen);
                continue;
            }

            for (var i = 1; i < stroke.Points.Count; i++)
            {
                var p0 = HandwritingEngine.SmoothedPoint(stroke.Points, i - 1);
                var p1 = HandwritingEngine.SmoothedPoint(stroke.Points, i);
                var width = HandwritingEngine.SegmentWidth(stroke, i);
                dc.DrawLine(new Pen(brush, width) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round }, p0, p1);
            }
        }

        if (!lightExport && PredictionEnabled && _activeStroke?.Points.Count >= 2)
        {
            var points = _activeStroke.Points;
            var p1 = points[^1];
            var predicted = HandwritingEngine.Predict(points, horizonMilliseconds: 10, maxDistance: 18);
            if (predicted == null) return;
            var color = ((SolidColorBrush)TextBrush(_activeStroke.Color, Appearance.Theme == PageTheme.Dark, false)).Color;
            var brush = new SolidColorBrush(Color.FromArgb(110, color.R, color.G, color.B));
            dc.DrawLine(new Pen(brush, HandwritingEngine.SegmentWidth(_activeStroke, points.Count - 1)), p1.ToPoint(), predicted.Value);
        }
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

    private void DrawTextItems(DrawingContext dc, bool lightExport)
    {
        if (Document == null) return;
        foreach (var item in Document.TextItems)
        {
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
            if (SelectedTool?.LassoShape == LassoShape.Rectangle && _lasso.Count >= 4) context.LineTo(_viewport.DocumentToView(_lasso[0].X, _lasso[0].Y), true, false);
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
            var radius = (SelectedTool?.Kind == DrawingToolKind.Eraser ? SelectedTool.EraserRadiusMm : 4.5) * UnitsPerMillimeter * _viewport.Scale;
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

    private void UpdatePointerCursor()
    {
        Cursor = TextInsertionArmed ? Cursors.IBeam : Cursors.Arrow;
    }

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
            BitmapSource result = source;
            if (inverted)
            {
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
                bitmap.Freeze(); result = bitmap;
            }
            _bitmapCache[key] = result;
            return result;
        }
        catch { return null; }
    }

    private bool HandleDoubleClick(Point viewPoint)
    {
        var text = HitText(viewPoint);
        if (text != null)
        {
            if (_selectedTextItems.Count == 1 && ReferenceEquals(_selectedTextItems[0], text)) return ActivateTextEditor(text);
            SelectOnlyText(text);
            return true;
        }
        var item = HitObject(viewPoint);
        if (item == null || item.IsPageBackground) return false;
        if (_selectedObjects.Count == 1 && ReferenceEquals(_selectedObjects[0], item))
        {
            ObjectActivated?.Invoke(this, new PageObjectEventArgs(item));
            return true;
        }
        SelectOnlyObject(item);
        return true;
    }

    private TextItem? HitText(Point viewPoint)
    {
        if (Document == null) return null;
        var doc = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        return Document.TextItems.LastOrDefault(value => new Rect(value.X, value.Y, value.Width, value.Height).Contains(doc));
    }

    private PageObject? HitObject(Point viewPoint)
    {
        if (Document == null) return null;
        var doc = _viewport.ViewToDocument(viewPoint.X, viewPoint.Y);
        return Document.Objects.LastOrDefault(value => new Rect(value.X, value.Y, value.Width, value.Height).Contains(doc));
    }

    private bool ActivateTextEditor(TextItem item)
    {
        ClearSelection();
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
        var extent = Appearance.PaperKind != PaperKind.Infinite
            ? new Rect(0, 0, Appearance.Width, Math.Max(1, Appearance.SheetCount) * Appearance.Height + Math.Max(0, Appearance.SheetCount - 1) * 32)
            : new Rect(0, 0, Math.Max(1200, Appearance.Width), Math.Max(1600, Appearance.Height));
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
        _undo.Push(Snapshot(Document));
        while (_undo.Count > 80)
        {
            var keep = _undo.Reverse().Take(80).Reverse().ToArray();
            _undo.Clear(); foreach (var entry in keep) _undo.Push(entry);
        }
        _redo.Clear();
    }

    private static string Snapshot(PageDocument document) => JsonSerializer.Serialize(document, SnapshotJson);

    private void RestoreSnapshot(string json)
    {
        if (Document == null) return;
        var restored = JsonSerializer.Deserialize<PageDocument>(json, SnapshotJson);
        if (restored == null) return;
        Document.Strokes = restored.Strokes;
        Document.Tags = restored.Tags;
        Document.TextItems = restored.TextItems;
        Document.Objects = restored.Objects;
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
        _viewport.CopyTo(Document.Viewport);
        if (immediate) { _saveTimer.Stop(); RaiseDocumentChanged(); }
        else { _saveTimer.Stop(); _saveTimer.Start(); }
    }

    private void GrowA4IfNeeded()
    {
        if (Document == null || Appearance.PaperKind != PaperKind.A4) return;
        var bottom = 0d;
        foreach (var stroke in Document.Strokes) if (!stroke.Bounds.IsEmpty) bottom = Math.Max(bottom, stroke.Bounds.Bottom);
        foreach (var tag in Document.Tags) bottom = Math.Max(bottom, tag.DocumentY + 18 * tag.Scale);
        foreach (var text in Document.TextItems) bottom = Math.Max(bottom, text.Y + text.Height);
        foreach (var item in Document.Objects) bottom = Math.Max(bottom, item.Y + item.Height);
        var margin = 24 * UnitsPerMillimeter;
        while (bottom >= (Appearance.SheetCount - 1) * (Appearance.Height + 32) + Appearance.Height - margin)
            Appearance.SheetCount++;
    }

    private void RaiseDocumentChanged() => DocumentChanged?.Invoke(this, EventArgs.Empty);

    private void ViewportMutated()
    {
        UpdateTextEditorPositions();
        if (Document != null) _viewport.CopyTo(Document.Viewport);
        ViewportChanged?.Invoke(this, EventArgs.Empty);
        MarkChanged();
        InvalidateVisual();
    }

    private void CommitViewport(PageDocument? document)
    {
        if (document != null) _viewport.CopyTo(document.Viewport);
    }

    private void ClearTransientState()
    {
        _activeStroke = null; _isPanning = false; _isLassoing = false; _isTransforming = false; _isInsertingSpace = false;
        _touchLassoDevice = null;
        _pressureCandidate = false; _temporaryPressureEraser = false; _pressureTimer.Stop(); _eraserViewPoint = null;
        ClearSelection();
    }

    private void ClearSelection(bool keepLasso = false)
    {
        _selectedStrokes.Clear(); _selectedTags.Clear(); _selectedTextItems.Clear(); _selectedObjects.Clear(); _selectionBounds = null;
        if (!keepLasso) _lasso.Clear();
        UpdateTextEditorInteraction();
    }

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
