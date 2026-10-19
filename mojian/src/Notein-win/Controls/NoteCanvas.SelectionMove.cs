using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Notein_win.Models;

namespace Notein_win.Controls;

public partial class NoteCanvas
{
    private Point? _pasteAnchor;
    private SelectionClipboardData? _moveOriginal;
    private DrawingGroup? _moveOriginalDrawing;
    private bool _moveCopyVisible;
    private bool _stylusDeselectOnly;

    private bool BeginPenBlankDeselection(Point viewPoint, bool isStylus, bool forceErase, DrawingToolKind kind)
    {
        if (!isStylus || forceErase || kind is not (DrawingToolKind.Pen or DrawingToolKind.Highlighter) ||
            !HasContentSelection() || HitText(viewPoint) != null || HitTag(viewPoint) != null ||
            HitStroke(viewPoint) != null || HitObject(viewPoint) is { IsPageBackground: false }) return false;
        ClearSelection();
        _stylusDeselectOnly = true;
        CaptureInput(isStylus: true);
        InvalidateVisual();
        return true;
    }

    // Remember the caret before ribbon/menu focus removes an empty temporary editor.
    private void RememberPasteAnchor(TextBox editor, TextItem item)
    {
        if (!editor.IsKeyboardFocusWithin) return;
        var caret = editor.GetRectFromCharacterIndex(editor.CaretIndex);
        _pasteAnchor = caret.IsEmpty ? new Point(item.X, item.Y) :
            _viewport.ViewToDocument(editor.TranslatePoint(caret.TopLeft, this).X,
                editor.TranslatePoint(caret.TopLeft, this).Y);
    }

    public Point PasteInsertionPoint => _pasteAnchor ?? _viewport.ViewToDocument(72, 110);

    private void BeginSelectionMove()
    {
        if (Document == null) return;
        _moveOriginal = new SelectionClipboardData(Document.NotebookId, Document.PageId,
            _selectedStrokes.Select(item => CloneStroke(item)).ToList(),
            _selectedTags.Select(item => CloneTag(item)).ToList(),
            _selectedTextItems.Select(item => CloneText(item)).ToList(),
            _selectedObjects.Select(item => new SelectionObjectClipboard(item.CloneWithNewId(0), null, null)).ToList(),
            _selectionBounds ?? Rect.Empty);
        _moveOriginalDrawing = RenderSelectionDrawing()?.Drawing;
        UpdateSelectionMoveCopy(Keyboard.Modifiers.HasFlag(ModifierKeys.Control));
    }

    internal void UpdateSelectionMoveCopy(bool control)
    {
        var visible = _moveOriginal != null && control;
        if (_moveCopyVisible == visible) return;
        _moveCopyVisible = visible;
        InvalidateVisual();
    }

    private void FinishSelectionMove(bool commit)
    {
        if (commit && _moveCopyVisible && _moveOriginal is { } original && Document != null)
        {
            Document.Strokes.AddRange(original.Strokes);
            Document.Tags.AddRange(original.Tags);
            Document.TextItems.AddRange(original.TextItems);
            Document.Objects.AddRange(original.Objects.Select(item => item.Item));
            // Keep selection on the moved content; the originals have independent identities.
            RebuildTextEditors();
        }
        _moveOriginal = null;
        _moveOriginalDrawing = null;
        _moveCopyVisible = false;
    }

    protected override void OnPreviewKeyDown(KeyEventArgs e)
    {
        base.OnPreviewKeyDown(e);
        if (e.Key is Key.LeftCtrl or Key.RightCtrl) UpdateSelectionMoveCopy(true);
        if (e.Key == Key.Escape && _stylusSelectionTransformActive)
        {
            CancelStylusToolActionForNavigation();
            e.Handled = true;
        }
        if (e.Key == Key.Escape && _directGestureMode != DirectGestureMode.None)
        {
            CancelDirectPointer();
            if (IsMouseCaptured) ReleaseMouseCapture();
            e.Handled = true;
        }
    }

    protected override void OnPreviewKeyUp(KeyEventArgs e)
    {
        base.OnPreviewKeyUp(e);
        if (e.Key is Key.LeftCtrl or Key.RightCtrl)
            UpdateSelectionMoveCopy(Keyboard.IsKeyDown(Key.LeftCtrl) || Keyboard.IsKeyDown(Key.RightCtrl));
    }
}
