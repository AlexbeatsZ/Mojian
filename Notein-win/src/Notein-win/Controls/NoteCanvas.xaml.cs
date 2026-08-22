using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Notein_win.Models;
using Notein_win.Services;

namespace Notein_win.Controls;

public partial class NoteCanvas : UserControl
{
    private readonly ViewportState _viewport = new();
    private readonly List<StrokeRecord> _strokes = new();
    private readonly List<CanvasTag> _tags = new();
    private readonly List<TextBox> _textEditors = new();
    private PageInsertDocument _insertDoc = new("notebook-personal", "page-overview");

    // Gesture and tool state
    private StrokeRecord? _activeStroke;
    private Point _lastMousePosition;
    private bool _isPanning;
    private bool _isLassoSelecting;
    private readonly List<Point> _lassoPolygon = new();
    private Rect? _selectionBounds;
    private readonly List<StrokeRecord> _selectedStrokes = new();
    private readonly List<CanvasTag> _selectedTags = new();
    private readonly List<InsertObject> _selectedObjects = new();
    private bool _isTransformingSelection;
    private Point _transformStartPoint;
    private int _resizeHandleIndex = -1; // -1: drag, 0: TL, 1: TR, 2: BR, 3: BL

    private bool _isInsertingSpace;
    private double _insertSpaceStartY;
    private double _insertSpaceCurrentY;

    private int? _focusedTagIndex;
    private Point? _eraserPoint;

    // Dependency Properties / Bindings
    public static readonly DependencyProperty BackgroundFormatProperty =
        DependencyProperty.Register(nameof(BackgroundFormat), typeof(CanvasBackgroundFormat), typeof(NoteCanvas),
            new FrameworkPropertyMetadata(CanvasBackgroundFormat.GridStandard, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty SelectedToolProperty =
        DependencyProperty.Register(nameof(SelectedTool), typeof(ToolPreset), typeof(NoteCanvas),
            new PropertyMetadata(null));

    public static readonly DependencyProperty SingleFingerPanEnabledProperty =
        DependencyProperty.Register(nameof(SingleFingerPanEnabled), typeof(bool), typeof(NoteCanvas),
            new PropertyMetadata(false));

    public static readonly DependencyProperty TextModeEnabledProperty =
        DependencyProperty.Register(nameof(TextModeEnabled), typeof(bool), typeof(NoteCanvas),
            new PropertyMetadata(false, OnTextModeChanged));

    public static readonly DependencyProperty InsertSpaceModeEnabledProperty =
        DependencyProperty.Register(nameof(InsertSpaceModeEnabled), typeof(bool), typeof(NoteCanvas),
            new PropertyMetadata(false));

    public static readonly DependencyProperty PendingTagKindProperty =
        DependencyProperty.Register(nameof(PendingTagKind), typeof(CanvasTagKind?), typeof(NoteCanvas),
            new PropertyMetadata(null));

    public CanvasBackgroundFormat BackgroundFormat
    {
        get => (CanvasBackgroundFormat)GetValue(BackgroundFormatProperty);
        set => SetValue(BackgroundFormatProperty, value);
    }

    public ToolPreset? SelectedTool
    {
        get => (ToolPreset?)GetValue(SelectedToolProperty);
        set => SetValue(SelectedToolProperty, value);
    }

    public bool SingleFingerPanEnabled
    {
        get => (bool)GetValue(SingleFingerPanEnabledProperty);
        set => SetValue(SingleFingerPanEnabledProperty, value);
    }

    public bool TextModeEnabled
    {
        get => (bool)GetValue(TextModeEnabledProperty);
        set => SetValue(TextModeEnabledProperty, value);
    }

    public bool InsertSpaceModeEnabled
    {
        get => (bool)GetValue(InsertSpaceModeEnabledProperty);
        set => SetValue(InsertSpaceModeEnabledProperty, value);
    }

    public CanvasTagKind? PendingTagKind
    {
        get => (CanvasTagKind?)GetValue(PendingTagKindProperty);
        set => SetValue(PendingTagKindProperty, value);
    }

    public ViewportState Viewport => _viewport;

    public NoteCanvas()
    {
        InitializeComponent();
        Focusable = true;
    }

    private static void OnTextModeChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is NoteCanvas canvas)
        {
            foreach (var editor in canvas._textEditors)
            {
                editor.IsEnabled = (bool)e.NewValue;
            }
        }
    }

    public void BindDocument(PageInsertDocument doc)
    {
        _insertDoc = doc;
        ClearSelection();
        InvalidateVisual();
    }

    public void Undo()
    {
        if (_strokes.Count > 0)
        {
            _strokes.RemoveAt(_strokes.Count - 1);
            ClearSelection();
            InvalidateVisual();
        }
    }

    public void ClearPage()
    {
        _strokes.Clear();
        _tags.Clear();
        _insertDoc.Objects.Clear();
        foreach (var editor in _textEditors)
        {
            CanvasContainer.Children.Remove(editor);
        }
        _textEditors.Clear();
        ClearSelection();
        InvalidateVisual();
    }

    public void FocusTag(int index)
    {
        if (index >= 0 && index < _tags.Count)
        {
            _focusedTagIndex = index;
            var tag = _tags[index];
            _viewport.CenterDocumentPoint(tag.DocumentX, tag.DocumentY, (float)(ActualWidth / 2), (float)(ActualHeight / 2));
            InvalidateVisual();
        }
    }

    public void ClearSelection()
    {
        _selectedStrokes.Clear();
        _selectedTags.Clear();
        _selectedObjects.Clear();
        _selectionBounds = null;
        _lassoPolygon.Clear();
        _isLassoSelecting = false;
        _isTransformingSelection = false;
    }

    #region Input Event Handling

    protected override void OnMouseWheel(MouseWheelEventArgs e)
    {
        base.OnMouseWheel(e);
        var pos = e.GetPosition(this);
        var factor = e.Delta > 0 ? 1.15f : 0.85f;
        _viewport.ZoomAt((float)pos.X, (float)pos.Y, factor);
        UpdateTextEditorPositions();
        InvalidateVisual();
    }

    protected override void OnMouseDown(MouseButtonEventArgs e)
    {
        base.OnMouseDown(e);
        Focus();
        var pos = e.GetPosition(this);
        _lastMousePosition = pos;

        // Middle button or Panorama Hand -> Pan
        if (e.MiddleButton == MouseButtonState.Pressed || SingleFingerPanEnabled)
        {
            _isPanning = true;
            CaptureMouse();
            return;
        }

        if (e.LeftButton == MouseButtonState.Pressed)
        {
            var docPt = _viewport.ViewToDocument(pos.X, pos.Y);

            // One-shot tag placement
            if (PendingTagKind.HasValue)
            {
                var tag = new CanvasTag(PendingTagKind.Value, (float)docPt.X, (float)docPt.Y);
                _tags.Add(tag);
                PendingTagKind = null;
                InvalidateVisual();
                return;
            }

            // Insert Space mode
            if (InsertSpaceModeEnabled)
            {
                _isInsertingSpace = true;
                _insertSpaceStartY = docPt.Y;
                _insertSpaceCurrentY = docPt.Y;
                CaptureMouse();
                InvalidateVisual();
                return;
            }

            // Text mode click -> create text editor
            if (TextModeEnabled)
            {
                AddTextEditor(pos.X, pos.Y);
                return;
            }

            // Check if clicking inside active selection or handles
            if (_selectionBounds.HasValue && SelectedTool?.Kind == DrawingToolKind.Lasso)
            {
                var viewBounds = _viewport.DocumentToView(_selectionBounds.Value);
                if (HitTestSelectionHandles(pos, viewBounds, out int handleIdx))
                {
                    _isTransformingSelection = true;
                    _resizeHandleIndex = handleIdx;
                    _transformStartPoint = docPt;
                    CaptureMouse();
                    return;
                }
                else if (viewBounds.Contains(pos))
                {
                    _isTransformingSelection = true;
                    _resizeHandleIndex = -1; // drag
                    _transformStartPoint = docPt;
                    CaptureMouse();
                    return;
                }
            }

            // Drawing Tool actions
            var tool = SelectedTool ?? ToolShelf.Defaults().First();
            switch (tool.Kind)
            {
                case DrawingToolKind.Pen:
                case DrawingToolKind.Highlighter:
                    _activeStroke = new StrokeRecord(
                        Guid.NewGuid().ToString(),
                        tool.Kind,
                        tool.Color,
                        tool.WidthMm,
                        tool.OpacityPercent,
                        new[] { new InkPoint((float)docPt.X, (float)docPt.Y, 0.6f) }
                    );
                    _strokes.Add(_activeStroke);
                    CaptureMouse();
                    InvalidateVisual();
                    break;

                case DrawingToolKind.Eraser:
                    _eraserPoint = pos;
                    PerformErase(docPt, tool.EraserRadiusMm * 3.78f);
                    CaptureMouse();
                    InvalidateVisual();
                    break;

                case DrawingToolKind.Lasso:
                    ClearSelection();
                    _isLassoSelecting = true;
                    _lassoPolygon.Clear();
                    _lassoPolygon.Add(docPt);
                    CaptureMouse();
                    InvalidateVisual();
                    break;
            }
        }
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        var pos = e.GetPosition(this);
        var docPt = _viewport.ViewToDocument(pos.X, pos.Y);

        if (_isPanning)
        {
            var deltaX = (float)(pos.X - _lastMousePosition.X);
            var deltaY = (float)(pos.Y - _lastMousePosition.Y);
            _viewport.Pan(deltaX, deltaY);
            _lastMousePosition = pos;
            UpdateTextEditorPositions();
            InvalidateVisual();
            return;
        }

        if (_isInsertingSpace)
        {
            _insertSpaceCurrentY = docPt.Y;
            InvalidateVisual();
            return;
        }

        if (_isTransformingSelection && _selectionBounds.HasValue)
        {
            var deltaX = docPt.X - _transformStartPoint.X;
            var deltaY = docPt.Y - _transformStartPoint.Y;

            if (_resizeHandleIndex == -1) // translate
            {
                TranslateSelection(deltaX, deltaY);
            }
            else // scale
            {
                var anchor = _resizeHandleIndex switch
                {
                    0 => new Point(_selectionBounds.Value.Right, _selectionBounds.Value.Bottom),
                    1 => new Point(_selectionBounds.Value.Left, _selectionBounds.Value.Bottom),
                    2 => new Point(_selectionBounds.Value.Left, _selectionBounds.Value.Top),
                    3 => new Point(_selectionBounds.Value.Right, _selectionBounds.Value.Top),
                    _ => new Point(_selectionBounds.Value.Left, _selectionBounds.Value.Top)
                };

                double initialDist = Math.Max(10, Math.Sqrt(SelectionGeometry.SquaredDistance(_transformStartPoint.X, _transformStartPoint.Y, anchor.X, anchor.Y)));
                double currentDist = Math.Max(10, Math.Sqrt(SelectionGeometry.SquaredDistance(docPt.X, docPt.Y, anchor.X, anchor.Y)));
                double factor = currentDist / initialDist;

                ScaleSelection(anchor, factor);
            }

            _transformStartPoint = docPt;
            InvalidateVisual();
            return;
        }

        if (_isLassoSelecting)
        {
            _lassoPolygon.Add(docPt);
            InvalidateVisual();
            return;
        }

        if (_activeStroke != null)
        {
            _activeStroke.Points.Add(new InkPoint((float)docPt.X, (float)docPt.Y, 0.6f));
            _activeStroke.ComputeBounds();
            InvalidateVisual();
            return;
        }

        if (e.LeftButton == MouseButtonState.Pressed && SelectedTool?.Kind == DrawingToolKind.Eraser)
        {
            _eraserPoint = pos;
            PerformErase(docPt, SelectedTool.EraserRadiusMm * 3.78f);
            InvalidateVisual();
            return;
        }
    }

    protected override void OnMouseUp(MouseButtonEventArgs e)
    {
        base.OnMouseUp(e);
        if (IsMouseCaptured) ReleaseMouseCapture();

        if (_isPanning)
        {
            _isPanning = false;
        }

        if (_isInsertingSpace)
        {
            _isInsertingSpace = false;
            var deltaY = (float)(_insertSpaceCurrentY - _insertSpaceStartY);
            if (Math.Abs(deltaY) > 2)
            {
                ApplyVerticalSpaceInsertion((float)_insertSpaceStartY, deltaY);
            }
            InvalidateVisual();
        }

        if (_isTransformingSelection)
        {
            _isTransformingSelection = false;
            RecomputeSelectionBounds();
            InvalidateVisual();
        }

        if (_isLassoSelecting)
        {
            _isLassoSelecting = false;
            ComputeLassoSelection();
            InvalidateVisual();
        }

        if (_activeStroke != null)
        {
            _activeStroke.ComputeBounds();
            _activeStroke = null;
            InvalidateVisual();
        }

        _eraserPoint = null;
        InvalidateVisual();
    }

    #endregion

    #region Erase, Selection & Space Insertion

    private void PerformErase(Point docPt, double radius)
    {
        // Erase complete strokes
        _strokes.RemoveAll(s => s.Points.Any(p => SelectionGeometry.SquaredDistance(p.X, p.Y, docPt.X, docPt.Y) <= radius * radius));

        // Erase tags
        _tags.RemoveAll(t => SelectionGeometry.SquaredDistance(t.DocumentX, t.DocumentY, docPt.X, docPt.Y) <= (radius + 15) * (radius + 15));

        // Erase objects
        _insertDoc.Objects.RemoveAll(o => new Rect(o.DocumentX, o.DocumentY, o.Width, o.Height).Contains(docPt));
    }

    private void ComputeLassoSelection()
    {
        _selectedStrokes.Clear();
        _selectedTags.Clear();
        _selectedObjects.Clear();

        if (_lassoPolygon.Count < 3)
        {
            _selectionBounds = null;
            return;
        }

        // Strokes
        foreach (var stroke in _strokes)
        {
            if (stroke.Points.Any(p => SelectionGeometry.PointInPolygon(p.X, p.Y, _lassoPolygon)))
            {
                _selectedStrokes.Add(stroke);
            }
        }

        // Tags
        foreach (var tag in _tags)
        {
            if (SelectionGeometry.PointInPolygon(tag.DocumentX, tag.DocumentY, _lassoPolygon))
            {
                _selectedTags.Add(tag);
            }
        }

        // Objects
        foreach (var obj in _insertDoc.Objects)
        {
            if (SelectionGeometry.PointInPolygon(obj.DocumentX + obj.Width / 2, obj.DocumentY + obj.Height / 2, _lassoPolygon))
            {
                _selectedObjects.Add(obj);
            }
        }

        RecomputeSelectionBounds();
    }

    private void RecomputeSelectionBounds()
    {
        if (_selectedStrokes.Count == 0 && _selectedTags.Count == 0 && _selectedObjects.Count == 0)
        {
            _selectionBounds = null;
            return;
        }

        Rect union = Rect.Empty;

        foreach (var stroke in _selectedStrokes)
        {
            union = union.IsEmpty ? stroke.Bounds : Rect.Union(union, stroke.Bounds);
        }

        foreach (var tag in _selectedTags)
        {
            var tagRect = new Rect(tag.DocumentX - 12, tag.DocumentY - 12, 24, 24);
            union = union.IsEmpty ? tagRect : Rect.Union(union, tagRect);
        }

        foreach (var obj in _selectedObjects)
        {
            var objRect = new Rect(obj.DocumentX, obj.DocumentY, obj.Width, obj.Height);
            union = union.IsEmpty ? objRect : Rect.Union(union, objRect);
        }

        union.Inflate(8, 8);
        _selectionBounds = union;
    }

    private void TranslateSelection(double dx, double dy)
    {
        foreach (var stroke in _selectedStrokes)
        {
            foreach (var pt in stroke.Points)
            {
                pt.X += (float)dx;
                pt.Y += (float)dy;
            }
            stroke.ComputeBounds();
        }

        foreach (var tag in _selectedTags)
        {
            tag.DocumentX += (float)dx;
            tag.DocumentY += (float)dy;
        }

        foreach (var obj in _selectedObjects)
        {
            obj.DocumentX += (float)dx;
            obj.DocumentY += (float)dy;
        }

        if (_selectionBounds.HasValue)
        {
            var b = _selectionBounds.Value;
            _selectionBounds = new Rect(b.X + dx, b.Y + dy, b.Width, b.Height);
        }
    }

    private void ScaleSelection(Point anchor, double factor)
    {
        foreach (var stroke in _selectedStrokes)
        {
            foreach (var pt in stroke.Points)
            {
                var scaled = SelectionGeometry.Scale(pt.X, pt.Y, anchor.X, anchor.Y, factor);
                pt.X = (float)scaled.X;
                pt.Y = (float)scaled.Y;
            }
            stroke.ComputeBounds();
        }

        foreach (var tag in _selectedTags)
        {
            var scaled = SelectionGeometry.Scale(tag.DocumentX, tag.DocumentY, anchor.X, anchor.Y, factor);
            tag.DocumentX = (float)scaled.X;
            tag.DocumentY = (float)scaled.Y;
        }

        foreach (var obj in _selectedObjects)
        {
            var scaled = SelectionGeometry.Scale(obj.DocumentX, obj.DocumentY, anchor.X, anchor.Y, factor);
            obj.DocumentX = (float)scaled.X;
            obj.DocumentY = (float)scaled.Y;
            obj.Width = (float)(obj.Width * factor);
            obj.Height = (float)(obj.Height * factor);
        }
    }

    private bool HitTestSelectionHandles(Point viewPt, Rect viewBounds, out int handleIdx)
    {
        handleIdx = -1;
        double radius = 10;
        Point[] handles = new[]
        {
            viewBounds.TopLeft,
            viewBounds.TopRight,
            viewBounds.BottomRight,
            viewBounds.BottomLeft
        };

        for (int i = 0; i < handles.Length; i++)
        {
            if (SelectionGeometry.SquaredDistance(viewPt.X, viewPt.Y, handles[i].X, handles[i].Y) <= radius * radius)
            {
                handleIdx = i;
                return true;
            }
        }
        return false;
    }

    private void ApplyVerticalSpaceInsertion(float startY, float deltaY)
    {
        foreach (var stroke in _strokes)
        {
            if (stroke.Bounds.Top >= startY)
            {
                foreach (var pt in stroke.Points) pt.Y += deltaY;
                stroke.ComputeBounds();
            }
        }

        foreach (var tag in _tags)
        {
            if (tag.DocumentY >= startY) tag.DocumentY += deltaY;
        }

        foreach (var obj in _insertDoc.Objects)
        {
            if (obj.DocumentY >= startY) obj.DocumentY += deltaY;
        }
    }

    #endregion

    #region Text Editors

    private void AddTextEditor(double viewX, double viewY)
    {
        var docPt = _viewport.ViewToDocument(viewX, viewY);
        var textBox = new TextBox
        {
            Width = 300,
            MinHeight = 32,
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            Background = Brushes.Transparent,
            Foreground = (Brush)FindResource("TextPrimary"),
            CaretBrush = (Brush)FindResource("TextPrimary"),
            BorderBrush = (Brush)FindResource("BorderHairline"),
            BorderThickness = new Thickness(1),
            FontSize = 16,
            FontFamily = new FontFamily("Segoe UI, Microsoft YaHei"),
            Tag = docPt
        };

        textBox.LostFocus += (s, e) =>
        {
            if (string.IsNullOrWhiteSpace(textBox.Text))
            {
                CanvasContainer.Children.Remove(textBox);
                _textEditors.Remove(textBox);
            }
        };

        _textEditors.Add(textBox);
        CanvasContainer.Children.Add(textBox);
        PositionTextEditor(textBox, docPt);
        textBox.Focus();
    }

    private void PositionTextEditor(TextBox editor, Point docPt)
    {
        var viewPt = _viewport.DocumentToView(docPt.X, docPt.Y);
        Canvas.SetLeft(editor, viewPt.X);
        Canvas.SetTop(editor, viewPt.Y);
    }

    private void UpdateTextEditorPositions()
    {
        foreach (var editor in _textEditors)
        {
            if (editor.Tag is Point docPt)
            {
                PositionTextEditor(editor, docPt);
            }
        }
    }

    #endregion

    #region Rendering

    protected override void OnRender(DrawingContext dc)
    {
        base.OnRender(dc);

        var bounds = new Rect(0, 0, ActualWidth, ActualHeight);
        dc.DrawRectangle((Brush)FindResource("PaperBackground"), null, bounds);

        // Transform context for document layer
        dc.PushTransform(new MatrixTransform(_viewport.DocumentToViewMatrix()));

        // 1. Draw Paper Background (Grids / Ruled lines / Dots)
        DrawPaperFormat(dc);

        // 2. Draw Inserted Objects (Tables, Attachments, Images, Spreadsheets, Links)
        DrawInsertObjects(dc);

        // 3. Draw Ink Strokes (Fountain Pen, Highlighters)
        DrawStrokes(dc);

        // 4. Draw Tags (Important stars, Question marks)
        DrawTags(dc);

        dc.Pop(); // Restore transform to view space

        // 5. Draw View-Space Overlays (Lasso tracing, Selection frame, Eraser circle, Insert space guide)
        DrawViewOverlays(dc);
    }

    private void DrawPaperFormat(DrawingContext dc)
    {
        var gridPen = new Pen((Brush)FindResource("GridLineBrush"), 0.8 / _viewport.Scale);
        gridPen.Freeze();

        var viewBoundsDoc = new Rect(
            _viewport.ViewToDocument(0, 0),
            _viewport.ViewToDocument(ActualWidth, ActualHeight)
        );

        double startX = Math.Floor(viewBoundsDoc.Left / 100) * 100 - 200;
        double endX = Math.Ceiling(viewBoundsDoc.Right / 100) * 100 + 200;
        double startY = Math.Floor(viewBoundsDoc.Top / 100) * 100 - 200;
        double endY = Math.Ceiling(viewBoundsDoc.Bottom / 100) * 100 + 200;

        double spacing = BackgroundFormat switch
        {
            CanvasBackgroundFormat.RuledNarrow => 28,
            CanvasBackgroundFormat.RuledStandard => 36,
            CanvasBackgroundFormat.RuledWide => 48,
            CanvasBackgroundFormat.GridDense => 20,
            CanvasBackgroundFormat.GridStandard => 32,
            CanvasBackgroundFormat.GridWide => 48,
            CanvasBackgroundFormat.DotsDense => 20,
            CanvasBackgroundFormat.DotsStandard => 32,
            CanvasBackgroundFormat.DotsWide => 48,
            _ => 0
        };

        if (spacing <= 0) return;

        bool isRuled = BackgroundFormat is CanvasBackgroundFormat.RuledNarrow or CanvasBackgroundFormat.RuledStandard or CanvasBackgroundFormat.RuledWide;
        bool isGrid = BackgroundFormat is CanvasBackgroundFormat.GridDense or CanvasBackgroundFormat.GridStandard or CanvasBackgroundFormat.GridWide;
        bool isDots = BackgroundFormat is CanvasBackgroundFormat.DotsDense or CanvasBackgroundFormat.DotsStandard or CanvasBackgroundFormat.DotsWide;

        if (isRuled || isGrid)
        {
            for (double y = Math.Floor(startY / spacing) * spacing; y <= endY; y += spacing)
            {
                dc.DrawLine(gridPen, new Point(startX, y), new Point(endX, y));
            }
        }

        if (isGrid)
        {
            for (double x = Math.Floor(startX / spacing) * spacing; x <= endX; x += spacing)
            {
                dc.DrawLine(gridPen, new Point(x, startY), new Point(x, endY));
            }
        }

        if (isDots)
        {
            var dotBrush = (Brush)FindResource("GridLineBrush");
            double dotRadius = 1.0 / _viewport.Scale;
            for (double y = Math.Floor(startY / spacing) * spacing; y <= endY; y += spacing)
            {
                for (double x = Math.Floor(startX / spacing) * spacing; x <= endX; x += spacing)
                {
                    dc.DrawEllipse(dotBrush, null, new Point(x, y), dotRadius, dotRadius);
                }
            }
        }
    }

    private void DrawInsertObjects(DrawingContext dc)
    {
        var borderPen = new Pen((Brush)FindResource("BorderHairline"), 1.0);
        var tableBgBrush = new SolidColorBrush(Color.FromRgb(27, 27, 27));
        var textBrush = (Brush)FindResource("TextPrimary");

        foreach (var obj in _insertDoc.Objects)
        {
            var rect = new Rect(obj.DocumentX, obj.DocumentY, obj.Width, obj.Height);

            switch (obj)
            {
                case TableObject table:
                    dc.DrawRectangle(tableBgBrush, borderPen, rect);
                    double cellW = table.Width / table.Columns;
                    double cellH = table.Height / table.Rows;
                    for (int c = 1; c < table.Columns; c++)
                    {
                        dc.DrawLine(borderPen, new Point(rect.X + c * cellW, rect.Y), new Point(rect.X + c * cellW, rect.Bottom));
                    }
                    for (int r = 1; r < table.Rows; r++)
                    {
                        dc.DrawLine(borderPen, new Point(rect.X, rect.Y + r * cellH), new Point(rect.Right, rect.Y + r * cellH));
                    }
                    break;

                case AttachmentObject attachment:
                    dc.DrawRoundedRectangle(new SolidColorBrush(Color.FromRgb(33, 33, 33)), borderPen, rect, 4, 4);
                    var iconRect = new Rect(rect.X + 8, rect.Y + 8, 36, rect.Height - 16);
                    dc.DrawRoundedRectangle((Brush)FindResource("AccentPurple"), null, iconRect, 3, 3);
                    DrawText(dc, attachment.DisplayName, new Point(rect.X + 52, rect.Y + 12), 14, textBrush);
                    DrawText(dc, $"{attachment.SizeBytes / 1024} KB · 已嵌入", new Point(rect.X + 52, rect.Y + 32), 11, (Brush)FindResource("TextSecondary"));
                    break;

                case ImageObject img:
                    dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(38, 38, 38)), borderPen, rect);
                    DrawText(dc, $"[图片: {img.DisplayName}]", new Point(rect.X + 12, rect.Y + rect.Height / 2 - 8), 12, (Brush)FindResource("TextSecondary"));
                    break;

                case SpreadsheetObject sheet:
                    dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(29, 29, 29)), borderPen, rect);
                    var headerRect = new Rect(rect.X, rect.Y, rect.Width, 36);
                    dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(28, 94, 57)), null, headerRect);
                    DrawText(dc, sheet.DisplayName, new Point(rect.X + 12, rect.Y + 8), 14, Brushes.White, true);
                    break;

                case NoteLinkObject link:
                    DrawText(dc, $"🔗 {link.TargetTitle}", new Point(rect.X, rect.Y), 15, (Brush)FindResource("AccentBlue"), false, true);
                    break;
            }
        }
    }

    private void DrawStrokes(DrawingContext dc)
    {
        foreach (var stroke in _strokes)
        {
            if (stroke.Points.Count < 2) continue;

            var baseColor = stroke.Color switch
            {
                InkPalette.Black => Color.FromRgb(240, 240, 240), // Logical black maps to light ink on dark paper
                InkPalette.Blue => Color.FromRgb(70, 153, 255),
                InkPalette.Red => Color.FromRgb(232, 49, 57),
                _ => Color.FromRgb(240, 240, 240)
            };

            if (stroke.Kind == DrawingToolKind.Highlighter)
            {
                byte alpha = (byte)(255 * stroke.OpacityPercent / 100);
                var hlColor = Color.FromArgb(alpha, baseColor.R, baseColor.G, baseColor.B);
                var hlPen = new Pen(new SolidColorBrush(hlColor), stroke.WidthMm * 3.78)
                {
                    StartLineCap = PenLineCap.Round,
                    EndLineCap = PenLineCap.Round,
                    LineJoin = PenLineJoin.Round
                };
                hlPen.Freeze();

                var geometry = new StreamGeometry();
                using (var ctx = geometry.Open())
                {
                    ctx.BeginFigure(stroke.Points[0].ToPoint(), false, false);
                    for (int i = 1; i < stroke.Points.Count; i++)
                    {
                        ctx.LineTo(stroke.Points[i].ToPoint(), true, false);
                    }
                }
                geometry.Freeze();
                dc.DrawGeometry(null, hlPen, geometry);
            }
            else // Fountain Pen with tapered mark simulation
            {
                var penBrush = new SolidColorBrush(baseColor);
                penBrush.Freeze();

                for (int i = 0; i < stroke.Points.Count - 1; i++)
                {
                    var p1 = stroke.Points[i];
                    var p2 = stroke.Points[i + 1];

                    // Taper width from pressure and stroke endpoints
                    double progress = (double)i / Math.Max(1, stroke.Points.Count - 1);
                    double taper = Math.Sin(progress * Math.PI) * 0.4 + 0.6;
                    double width = Math.Max(0.8, stroke.WidthMm * 3.78 * p1.Pressure * taper);

                    var segmentPen = new Pen(penBrush, width)
                    {
                        StartLineCap = PenLineCap.Round,
                        EndLineCap = PenLineCap.Round
                    };
                    segmentPen.Freeze();

                    dc.DrawLine(segmentPen, p1.ToPoint(), p2.ToPoint());
                }
            }
        }
    }

    private void DrawTags(DrawingContext dc)
    {
        var tagBrush = (Brush)FindResource("TagRed");
        var focusPen = new Pen((Brush)FindResource("TagRed"), 2.0 / _viewport.Scale);

        for (int i = 0; i < _tags.Count; i++)
        {
            var tag = _tags[i];
            var center = new Point(tag.DocumentX, tag.DocumentY);

            if (tag.Kind == CanvasTagKind.Important)
            {
                DrawStar(dc, center, 10, tagBrush);
            }
            else
            {
                DrawText(dc, "?", new Point(center.X - 6, center.Y - 14), 22, tagBrush, true);
            }

            if (_focusedTagIndex == i)
            {
                dc.DrawEllipse(null, focusPen, center, 16, 16);
            }
        }
    }

    private void DrawStar(DrawingContext dc, Point center, double radius, Brush brush)
    {
        var geometry = new StreamGeometry();
        using (var ctx = geometry.Open())
        {
            double innerRadius = radius * 0.45;
            for (int i = 0; i < 10; i++)
            {
                double r = i % 2 == 0 ? radius : innerRadius;
                double angle = -Math.PI / 2 + i * Math.PI / 5;
                var pt = new Point(center.X + Math.Cos(angle) * r, center.Y + Math.Sin(angle) * r);
                if (i == 0) ctx.BeginFigure(pt, true, true);
                else ctx.LineTo(pt, true, false);
            }
        }
        geometry.Freeze();
        dc.DrawGeometry(brush, null, geometry);
    }

    private void DrawViewOverlays(DrawingContext dc)
    {
        // 1. Lasso polyline preview
        if (_isLassoSelecting && _lassoPolygon.Count > 1)
        {
            var lassoPen = new Pen((Brush)FindResource("AccentBlue"), 1.5)
            {
                DashStyle = new DashStyle(new[] { 4.0, 3.0 }, 0)
            };
            var geo = new StreamGeometry();
            using (var ctx = geo.Open())
            {
                var p0 = _viewport.DocumentToView(_lassoPolygon[0].X, _lassoPolygon[0].Y);
                ctx.BeginFigure(p0, false, false);
                for (int i = 1; i < _lassoPolygon.Count; i++)
                {
                    ctx.LineTo(_viewport.DocumentToView(_lassoPolygon[i].X, _lassoPolygon[i].Y), true, false);
                }
            }
            dc.DrawGeometry(null, lassoPen, geo);
        }

        // 2. Selection frame with handles
        if (_selectionBounds.HasValue)
        {
            var viewBounds = _viewport.DocumentToView(_selectionBounds.Value);
            var fillBrush = (Brush)FindResource("SelectionFill");
            var strokePen = new Pen((Brush)FindResource("SelectionStroke"), 1.2);
            dc.DrawRectangle(fillBrush, strokePen, viewBounds);

            // 4 corner scale handles
            var handleBrush = Brushes.White;
            var handlePen = new Pen((Brush)FindResource("SelectionStroke"), 1.5);
            Point[] corners = new[] { viewBounds.TopLeft, viewBounds.TopRight, viewBounds.BottomRight, viewBounds.BottomLeft };
            foreach (var corner in corners)
            {
                dc.DrawEllipse(handleBrush, handlePen, corner, 5, 5);
            }
        }

        // 3. Eraser circle
        if (_eraserPoint.HasValue && SelectedTool?.Kind == DrawingToolKind.Eraser)
        {
            double r = SelectedTool.EraserRadiusMm * 3.78 * _viewport.Scale;
            var eraserPen = new Pen((Brush)FindResource("TextSecondary"), 1.5);
            dc.DrawEllipse(null, eraserPen, _eraserPoint.Value, r, r);
        }

        // 4. Insert Space Guide
        if (_isInsertingSpace)
        {
            var viewStartY = _viewport.DocumentToView(0, _insertSpaceStartY).Y;
            var viewCurY = _viewport.DocumentToView(0, _insertSpaceCurrentY).Y;

            var guidePen = new Pen((Brush)FindResource("AccentPurple"), 1.5)
            {
                DashStyle = DashStyles.Dash
            };
            dc.DrawLine(guidePen, new Point(0, viewStartY), new Point(ActualWidth, viewStartY));
            dc.DrawLine(guidePen, new Point(0, viewCurY), new Point(ActualWidth, viewCurY));

            DrawText(dc, $"⬇ 插入空间: {Math.Round(_insertSpaceCurrentY - _insertSpaceStartY)} px", new Point(20, viewCurY - 24), 13, (Brush)FindResource("AccentPurple"), true);
        }
    }

    private void DrawText(DrawingContext dc, string text, Point location, double size, Brush brush, bool bold = false, bool underline = false)
    {
        var formattedText = new FormattedText(
            text,
            CultureInfo.CurrentCulture,
            FlowDirection.LeftToRight,
            new Typeface(new FontFamily("Segoe UI, Microsoft YaHei"), FontStyles.Normal, bold ? FontWeights.Bold : FontWeights.Normal, FontStretches.Normal),
            size,
            brush,
            VisualTreeHelper.GetDpi(this).PixelsPerDip
        );

        if (underline)
        {
            formattedText.SetTextDecorations(TextDecorations.Underline);
        }

        dc.DrawText(formattedText, location);
    }

    #endregion
}
