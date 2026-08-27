using System.Windows;

namespace Notein_win.Services;

public static class SelectionGeometry
{
    public static bool PointInPolygon(double px, double py, IList<Point> polygon)
    {
        if (polygon.Count < 3) return false;

        bool inside = false;
        int count = polygon.Count;
        for (int i = 0, j = count - 1; i < count; j = i++)
        {
            var pi = polygon[i];
            var pj = polygon[j];

            if (((pi.Y > py) != (pj.Y > py)) &&
                (px < (pj.X - pi.X) * (py - pi.Y) / (pj.Y - pi.Y + 1e-10) + pi.X))
            {
                inside = !inside;
            }
        }
        return inside;
    }

    public static double SquaredDistance(double x1, double y1, double x2, double y2)
    {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    public static Point Translate(double x, double y, double dx, double dy) =>
        new(x + dx, y + dy);

    public static Point Scale(double x, double y, double anchorX, double anchorY, double factor) =>
        new(anchorX + (x - anchorX) * factor, anchorY + (y - anchorY) * factor);

    public static Point Scale(double x, double y, double anchorX, double anchorY, double factorX, double factorY) =>
        new(anchorX + (x - anchorX) * factorX, anchorY + (y - anchorY) * factorY);

    public static IReadOnlyList<Point> SelectionHandles(Rect bounds) => new[]
    {
        bounds.TopLeft,
        new Point(bounds.Left + bounds.Width / 2, bounds.Top),
        bounds.TopRight,
        new Point(bounds.Right, bounds.Top + bounds.Height / 2),
        bounds.BottomRight,
        new Point(bounds.Left + bounds.Width / 2, bounds.Bottom),
        bounds.BottomLeft,
        new Point(bounds.Left, bounds.Top + bounds.Height / 2),
    };

    public static Rect ResizeBounds(Rect start, int handle, Point point, double minimumSize)
    {
        if (start.IsEmpty || handle is < 0 or > 7) return start;
        var left = start.Left;
        var top = start.Top;
        var right = start.Right;
        var bottom = start.Bottom;
        switch (handle)
        {
            case 0: left = Math.Min(point.X, right - minimumSize); top = Math.Min(point.Y, bottom - minimumSize); break;
            case 1: top = Math.Min(point.Y, bottom - minimumSize); break;
            case 2: right = Math.Max(point.X, left + minimumSize); top = Math.Min(point.Y, bottom - minimumSize); break;
            case 3: right = Math.Max(point.X, left + minimumSize); break;
            case 4: right = Math.Max(point.X, left + minimumSize); bottom = Math.Max(point.Y, top + minimumSize); break;
            case 5: bottom = Math.Max(point.Y, top + minimumSize); break;
            case 6: left = Math.Min(point.X, right - minimumSize); bottom = Math.Max(point.Y, top + minimumSize); break;
            case 7: left = Math.Min(point.X, right - minimumSize); break;
        }
        return new Rect(new Point(left, top), new Point(right, bottom));
    }

    public static bool PolygonIntersectsRect(IList<Point> polygon, Rect rect)
    {
        if (polygon.Count < 3 || rect.IsEmpty) return false;
        var corners = new[] { rect.TopLeft, rect.TopRight, rect.BottomRight, rect.BottomLeft };
        if (corners.Any(point => PointInPolygon(point.X, point.Y, polygon))) return true;
        if (polygon.Any(rect.Contains)) return true;
        for (var polygonIndex = 0; polygonIndex < polygon.Count; polygonIndex++)
        {
            var start = polygon[polygonIndex];
            var end = polygon[(polygonIndex + 1) % polygon.Count];
            for (var cornerIndex = 0; cornerIndex < corners.Length; cornerIndex++)
            {
                if (SegmentsIntersect(start, end, corners[cornerIndex], corners[(cornerIndex + 1) % corners.Length])) return true;
            }
        }
        return false;
    }

    public static bool PolygonContainsRect(IList<Point> polygon, Rect rect)
    {
        if (polygon.Count < 3 || rect.IsEmpty) return false;
        return new[] { rect.TopLeft, rect.TopRight, rect.BottomRight, rect.BottomLeft }
            .All(point => PointInPolygonOrBoundary(point, polygon));
    }

    public static bool PointInPolygonOrBoundary(Point point, IList<Point> polygon)
    {
        if (PointInPolygon(point.X, point.Y, polygon)) return true;
        for (var index = 0; index < polygon.Count; index++)
        {
            var start = polygon[index];
            var end = polygon[(index + 1) % polygon.Count];
            if (Math.Abs(Orientation(start, end, point)) < 0.0001 && OnSegment(start, end, point)) return true;
        }
        return false;
    }

    public static bool LineIntersectsCircle(Point p1, Point p2, Point center, double radius)
    {
        double dx = p2.X - p1.X;
        double dy = p2.Y - p1.Y;
        double lenSq = dx * dx + dy * dy;

        if (lenSq < 1e-6)
        {
            return SquaredDistance(p1.X, p1.Y, center.X, center.Y) <= radius * radius;
        }

        double t = Math.Clamp(((center.X - p1.X) * dx + (center.Y - p1.Y) * dy) / lenSq, 0.0, 1.0);
        double nearestX = p1.X + t * dx;
        double nearestY = p1.Y + t * dy;

        return SquaredDistance(nearestX, nearestY, center.X, center.Y) <= radius * radius;
    }

    private static bool SegmentsIntersect(Point firstStart, Point firstEnd, Point secondStart, Point secondEnd)
    {
        var firstA = Orientation(firstStart, firstEnd, secondStart);
        var firstB = Orientation(firstStart, firstEnd, secondEnd);
        var secondA = Orientation(secondStart, secondEnd, firstStart);
        var secondB = Orientation(secondStart, secondEnd, firstEnd);
        if (firstA * firstB < 0 && secondA * secondB < 0) return true;
        return (Math.Abs(firstA) < 0.0001 && OnSegment(firstStart, firstEnd, secondStart)) ||
               (Math.Abs(firstB) < 0.0001 && OnSegment(firstStart, firstEnd, secondEnd)) ||
               (Math.Abs(secondA) < 0.0001 && OnSegment(secondStart, secondEnd, firstStart)) ||
               (Math.Abs(secondB) < 0.0001 && OnSegment(secondStart, secondEnd, firstEnd));
    }

    private static double Orientation(Point start, Point end, Point point) =>
        (end.X - start.X) * (point.Y - start.Y) - (end.Y - start.Y) * (point.X - start.X);

    private static bool OnSegment(Point start, Point end, Point point) =>
        point.X >= Math.Min(start.X, end.X) && point.X <= Math.Max(start.X, end.X) &&
        point.Y >= Math.Min(start.Y, end.Y) && point.Y <= Math.Max(start.Y, end.Y);
}
