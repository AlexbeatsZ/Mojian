using System.IO;
using System.Windows;
using System.Windows.Media;
using Notein_win.Models;

namespace Notein_win.Controls;

public partial class NoteCanvas
{
    private long _pdfSceneRevision;

    // WPF re-tessellates every vector drawing that intersects a dirty region on each frame.
    // Without a cache, the wet stroke or eraser cursor re-rasterizes all old ink beneath it.
    // On screen the cache is pixel-identical to vector rendering; an offscreen RenderTargetBitmap
    // of the control resamples it, so tests that compare vector handoff pixels can turn it off.
    private bool _staticSceneCacheEnabled = true;
    internal bool StaticSceneCacheEnabled
    {
        get => _staticSceneCacheEnabled;
        set { _staticSceneCacheEnabled = value; if (!_panCacheActive) SceneLayer.CacheMode = StaticSceneCache; }
    }
    private readonly BitmapCache _staticSceneCache = new() { EnableClearType = false, SnapsToDevicePixels = false, RenderAtScale = 1 };
    private CacheMode? StaticSceneCache => _staticSceneCacheEnabled ? _staticSceneCache : null;

    private StrokeRecord? _topmostCheckedStroke;
    private bool _topmostCheckResult;

    // New ink on an imported page normally paints above every source item. Then the overlay is
    // exact; otherwise wet ink stays at its canonical depth under the covering content.
    private bool WetInkIsTopmost(PageDocument document, StrokeRecord stroke)
    {
        if (ReferenceEquals(_topmostCheckedStroke, stroke)) return _topmostCheckResult;
        _topmostCheckedStroke = stroke;
        _topmostCheckResult = false;
        if (document.Tags.Count > 0) return false;
        var layers = new Dictionary<string, PageLayer>();
        foreach (var layer in document.Layers) layers.TryAdd(layer.Id, layer);
        int Order(string id) => layers.TryGetValue(id, out var layer) ? layer.Order : int.MaxValue;
        bool Visible(string id) => !layers.TryGetValue(id, out var layer) || layer.Visible;
        var strokeIndex = -1;
        for (var i = 0; i < document.Strokes.Count; i++)
            if (ReferenceEquals(document.Strokes[i], stroke)) { strokeIndex = document.Objects.Count + i; break; }
        if (strokeIndex < 0) return false;
        var order = Order(stroke.LayerId);
        bool Above(string layerId, long zOrder, int index, bool background = false)
        {
            if (!Visible(layerId)) return false;
            var itemOrder = background ? int.MinValue : Order(layerId);
            return itemOrder > order || itemOrder == order && (zOrder > stroke.ZOrder || zOrder == stroke.ZOrder && index > strokeIndex);
        }
        for (var i = 0; i < document.Objects.Count; i++)
            if (Above(document.Objects[i].LayerId, document.Objects[i].ZOrder, i, document.Objects[i].IsPageBackground)) return false;
        for (var i = 0; i < document.Strokes.Count; i++)
            if (!ReferenceEquals(document.Strokes[i], stroke) && Above(document.Strokes[i].LayerId, document.Strokes[i].ZOrder, document.Objects.Count + i)) return false;
        for (var i = 0; i < document.TextItems.Count; i++)
            if (Above(document.TextItems[i].LayerId, document.TextItems[i].ZOrder, document.Objects.Count + document.Strokes.Count + i)) return false;
        return _topmostCheckResult = true;
    }

    private object PaperSceneSignature(Rect extent) =>
        (Appearance.PaperKind, Appearance.Theme, Appearance.Background, Appearance.PageColor,
         Appearance.SourcePageColor, Appearance.ShowTitle, Appearance.SheetCount, Appearance.Width,
         Appearance.Height, Appearance.InfiniteWidth, Appearance.InfiniteHeight, _importedPaperBounds,
         PageTitle, Document?.PageId, extent, _viewport.Scale, VisualTreeHelper.GetDpi(this).PixelsPerDip);

    private void AddScreenObject(PageObject item, Rect extent)
    {
        // A page-sized image/PDF must stay retained when a pen contacts or lifts, too.
        // Tables and editable text still use the normal refresh path for mutable cell content.
        object? signature = item.IsImageLike ?
            (item.Kind, item.X, item.Y, item.Width, item.Height, item.AssetName, item.SourceAssetName,
             item.SourcePageNumber, item.ViewInverted, item.RotationQuarterTurns, item.FlipHorizontal,
             item.FlipVertical, item.CropLeft, item.CropTop, item.CropRight, item.CropBottom,
             item.DisplayName, Appearance.Theme, Appearance.PageColor, Appearance.SourcePageColor,
             Document?.PageId, ReferenceEquals(item, _cropImage) ? _cropPreviewBounds : null,
             item.Kind == PageObjectKind.PdfPrintout ? (_viewport.Scale, _pdfSceneRevision) : default,
             SceneAssetStamp(item.AssetName), SceneAssetStamp(item.SourceAssetName),
             VisualTreeHelper.GetDpi(this).PixelsPerDip) : null;
        SceneLayer.DrawPart(item, signature, context => DrawObjects(context, false, extent, [item]));
    }

    private (string? Path, long Modified) SceneAssetStamp(string name)
    {
        var path = string.IsNullOrEmpty(name) ? null : ResolveAssetPath?.Invoke(name);
        return (path, string.IsNullOrWhiteSpace(path) ? 0 : File.GetLastWriteTimeUtc(path).Ticks);
    }
}
