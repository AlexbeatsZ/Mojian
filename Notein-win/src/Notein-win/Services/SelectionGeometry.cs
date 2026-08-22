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
}
