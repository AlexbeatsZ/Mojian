using System.Globalization;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using Notein_win.Models;

namespace Notein_win.Controls;

public sealed class PenCurveEditor : FrameworkElement
{
    public List<CurvePoint> Points { get; set; } = [];
    public double MaximumX { get; set; } = 1;
    public string AxisLabel { get; set; } = "压力 %";
    public event Action? Changed;
    private int _selected = -1;
    private Rect Plot => new(42, 12, Math.Max(1, ActualWidth - 58), Math.Max(1, ActualHeight - 38));
    private Point Pixel(CurvePoint p) => new(Plot.Left + p.X / MaximumX * Plot.Width, Plot.Bottom - p.Y / 3 * Plot.Height);
    private static readonly Brush Accent = new SolidColorBrush(Color.FromRgb(193, 163, 224));
    private static readonly Brush Muted = new SolidColorBrush(Color.FromRgb(160, 160, 165));

    public PenCurveEditor()
    {
        Height = 146; Focusable = true; Cursor = Cursors.Cross;
        ToolTip = "点击添加控制点，拖动调整；右键或 Delete 删除所选控制点。";
    }

    protected override void OnRender(DrawingContext dc)
    {
        dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(27, 27, 29)), null, new Rect(RenderSize));
        var plot = Plot;
        var grid = new Pen(new SolidColorBrush(Color.FromRgb(56, 56, 59)), 1);
        for (var i = 0; i <= 4; i++) dc.DrawLine(grid, new Point(plot.Left + plot.Width * i / 4, plot.Top), new Point(plot.Left + plot.Width * i / 4, plot.Bottom));
        for (var i = 0; i <= 3; i++)
        {
            var y = plot.Bottom - plot.Height * i / 3;
            dc.DrawLine(grid, new Point(plot.Left, y), new Point(plot.Right, y));
            Label(dc, i * 100 + "%", new Point(3, y - 7));
        }
        Label(dc, "0", new Point(plot.Left, plot.Bottom + 5));
        Label(dc, MaximumX == 1 ? "100" : MaximumX.ToString("0", CultureInfo.InvariantCulture), new Point(plot.Right - 19, plot.Bottom + 5));
        Label(dc, AxisLabel, new Point(plot.Left + plot.Width / 2 - 30, plot.Bottom + 5));
        var pen = new Pen(Accent, 2);
        for (var i = 1; i < Points.Count; i++) dc.DrawLine(pen, Pixel(Points[i - 1]), Pixel(Points[i]));
        for (var i = 0; i < Points.Count; i++) dc.DrawEllipse(i == _selected ? Brushes.White : Accent, null, Pixel(Points[i]), i == _selected ? 5 : 3.5, i == _selected ? 5 : 3.5);
    }

    private void Label(DrawingContext dc, string text, Point p) => dc.DrawText(new FormattedText(text, CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
        new Typeface("Segoe UI"), 10, Muted, VisualTreeHelper.GetDpi(this).PixelsPerDip), p);

    protected override void OnMouseDown(MouseButtonEventArgs e)
    {
        Focus(); var p = e.GetPosition(this);
        _selected = Enumerable.Range(0, Points.Count).OrderBy(i => (Pixel(Points[i]) - p).Length).FirstOrDefault(-1);
        if (_selected >= 0 && (Pixel(Points[_selected]) - p).Length > 12) _selected = -1;
        if (e.ChangedButton == MouseButton.Right) { DeleteSelected(); e.Handled = true; return; }
        if (!Plot.Contains(p)) return;
        if (_selected < 0 && Points.Count < 33)
        {
            var x = Math.Clamp((p.X - Plot.Left) / Plot.Width * MaximumX, .001 * MaximumX, .999 * MaximumX);
            if (Points.All(point => Math.Abs(point.X - x) > MaximumX * .001))
            {
                var point = new CurvePoint(x, Math.Clamp((Plot.Bottom - p.Y) / Plot.Height * 3, 0, 3));
                Points.Add(point); Points.Sort((a, b) => a.X.CompareTo(b.X)); _selected = Points.IndexOf(point);
                Changed?.Invoke();
            }
        }
        if (_selected >= 0) CaptureMouse();
        InvalidateVisual(); e.Handled = true;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        if (!IsMouseCaptured || _selected < 0) return;
        var p = e.GetPosition(this);
        MoveSelected((p.X - Plot.Left) / Plot.Width * MaximumX, (Plot.Bottom - p.Y) / Plot.Height * 3);
    }
    protected override void OnMouseUp(MouseButtonEventArgs e) { if (IsMouseCaptured) ReleaseMouseCapture(); }
    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.Key is Key.Delete or Key.Back) { DeleteSelected(); e.Handled = true; }
        else if (_selected >= 0 && e.Key is Key.Left or Key.Right or Key.Up or Key.Down)
        {
            var p = Points[_selected];
            MoveSelected(p.X + (e.Key == Key.Left ? -.01 : e.Key == Key.Right ? .01 : 0) * MaximumX,
                p.Y + (e.Key == Key.Down ? -.01 : e.Key == Key.Up ? .01 : 0));
            e.Handled = true;
        }
    }
    private void MoveSelected(double x, double y)
    {
        var fixedX = _selected == 0 || _selected == Points.Count - 1;
        x = fixedX ? Points[_selected].X : Math.Clamp(x, Points[_selected - 1].X + MaximumX * .0001, Points[_selected + 1].X - MaximumX * .0001);
        Points[_selected] = new(Math.Round(x, 6), Math.Round(Math.Clamp(y, 0, 3), 4));
        Changed?.Invoke(); InvalidateVisual();
    }
    public void DeleteSelected()
    {
        if (_selected <= 0 || _selected >= Points.Count - 1) return;
        Points.RemoveAt(_selected); _selected = -1; Changed?.Invoke(); InvalidateVisual();
    }
}
