using System.IO;
using System.Windows;
using System.Windows.Media;
using Notein_win.Models;

namespace Notein_win.Controls;

public partial class NoteCanvas
{
    private long _pdfSceneRevision;

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
