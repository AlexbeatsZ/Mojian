using System.Windows;
using Notein_win.Models;

namespace Notein_win.Services;

public static class StrokeViewportCulling
{
    public static bool IsVisible(StrokeRecord stroke, Rect visibleDocumentBounds)
    {
        var bounds = stroke.Bounds;
        return !bounds.IsEmpty && bounds.IntersectsWith(visibleDocumentBounds);
    }
}
