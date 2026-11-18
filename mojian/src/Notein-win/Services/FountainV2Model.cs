using System.Globalization;
using System.Windows;
using System.Windows.Media;
using Notein_win.Models;

namespace Notein_win.Services;

// Clean-room Notein calibration. Old fountain-v1 rendering intentionally stays in HandwritingEngine.
public static class FountainV2Model
{
    public const string Profile = "fountain-v2";
    private const double UnitsPerMm = 96d / 25.4;
    private static readonly (double Speed, double Thinning)[] SpeedCurve =
        FountainV2Contract.SpeedCurve;

    public static int StabilityWindowMillis(int percent)
    {
        var p = Math.Clamp(percent, 0, 100);
        return Math.Max(1, (int)Math.Floor((p <= 50 ? FountainV2Contract.StabilityMiddle * p / 50 : FountainV2Contract.StabilityMiddle +
            (FountainV2Contract.StabilityMaximum - FountainV2Contract.StabilityMiddle) * (p - 50) / 50) + .5));
    }

    public static double SpeedThinning(double centimetersPerSecond)
    {
        for (var i = 1; i < SpeedCurve.Length; i++)
        {
            var (speed, thinning) = SpeedCurve[i];
            if (centimetersPerSecond > speed) continue;
            var previous = SpeedCurve[i - 1];
            return previous.Thinning + (thinning - previous.Thinning) *
                Math.Clamp((centimetersPerSecond - previous.Speed) / (speed - previous.Speed), 0, 1);
        }
        return FountainV2Contract.ThinningCap;
    }

    public static double DirectionMultiplier(double directionY) => 1 + FountainV2Contract.DirectionAmount * Math.Clamp((directionY - FountainV2Contract.DirectionMinimum) / FountainV2Contract.DirectionRange, 0, 1);

    public static double WidthMultiplier(double pressure, int pressurePercent, double speedCmPerSecond, int flowPercent, double directionY)
    {
        var gamma = Math.Clamp(pressurePercent / 100d, 0, 4);
        var p = Math.Clamp(pressure, 0, 1);
        // AndroidX uses the same 16-segment pressure curve, including its p^0 contact step.
        var segments = FountainV2Contract.PressureSegments;
        var part = Math.Min(segments - 1, (int)(p * segments));
        var pressureValue = gamma == 0 ? 1 :
            Math.Pow(part / (double)segments, gamma) * (1 - (p * segments - part)) +
            Math.Pow((part + 1) / (double)segments, gamma) * (p * segments - part);
        // Match Android's clamped endpoint (important above 100% flow), not a negative width.
        var end = Math.Max(FountainV2Contract.MinimumSpeedMultiplier, 1 - FountainV2Contract.ThinningCap * Math.Clamp(flowPercent / 100d, 0, 2));
        return FountainV2Contract.FullPressureWidth * pressureValue * (1 - (1 - end) * SpeedThinning(speedCmPerSecond) / FountainV2Contract.ThinningCap) * DirectionMultiplier(directionY);
    }

    public readonly record struct Sample(Point Position, double Pressure, double Milliseconds, double Width);
    private readonly record struct Value(double X, double Y, double P)
    {
        public static Value operator +(Value a, Value b) => new(a.X + b.X, a.Y + b.Y, a.P + b.P);
        public static Value operator -(Value a, Value b) => new(a.X - b.X, a.Y - b.Y, a.P - b.P);
        public static Value operator *(Value a, double b) => new(a.X * b, a.Y * b, a.P * b);
    }

    public static IReadOnlyList<Sample> Model(StrokeRecord stroke)
    {
        var count = stroke.Points.Count;
        if (count == 0) return [];
        var times = new double[count];
        var values = new Value[count];
        var integrals = new Value[count];
        for (var i = 0; i < count; i++)
        {
            var p = stroke.Points[i];
            times[i] = i == 0 ? 0 : Math.Max(times[i - 1] + 1, p.ElapsedMilliseconds - stroke.Points[0].ElapsedMilliseconds);
            values[i] = new Value(p.X, p.Y, Math.Clamp(p.Pressure, 0, 1));
            if (i > 0) integrals[i] = integrals[i - 1] + (values[i - 1] + values[i]) * ((times[i] - times[i - 1]) / 2);
        }
        var modeledInputs = new List<(Point Position, double Pressure, double Time)>();
        var window = StabilityWindowMillis(stroke.PenStyle?.StabilityPercent ?? stroke.StabilityPercent);
        if (stroke.PenStyle is { InputWindowMs: > 0 } inputStyle) window = inputStyle.InputWindowMs;
        var left = 0;
        var right = 0;
        var unitCm = stroke.StrokeUnitLengthCm > 0 ? stroke.StrokeUnitLengthCm : 2.54 / 96;
        Value At(double time, int segment)
        {
            if (segment >= count - 1) return values[^1];
            var fraction = (time - times[segment]) / (times[segment + 1] - times[segment]);
            return values[segment] + (values[segment + 1] - values[segment]) * fraction;
        }
        Value IntegralAt(double time, int segment) => integrals[segment] +
            (values[segment] + At(time, segment)) * ((time - times[segment]) / 2);
        void Append(double time)
        {
            // AndroidX uses a centered time average, shrinking BOTH sides at the endpoints.
            // A trailing average would pull the finished line behind the physical nib.
            var half = Math.Min(window / 2d, Math.Min(time, times[^1] - time));
            var start = time - half;
            var end = time + half;
            while (right + 1 < count && times[right + 1] <= end) right++;
            while (left + 1 < count && times[left + 1] <= start) left++;
            var modeled = half <= 1e-8 ? At(time, left) : (IntegralAt(end, right) - IntegralAt(start, left)) * (1 / (end - start));
            modeledInputs.Add((new Point(modeled.X, modeled.Y), modeled.P, time));
        }
        // At least 180 Hz; retaining actual input times avoids discarding pressure edges.
        Append(0);
        for (var i = 1; i < count; i++)
        {
            var steps = (int)Math.Min(100, Math.Ceiling((times[i] - times[i - 1]) / (1000d / (stroke.PenStyle?.UpsamplingHz ?? FountainV2Contract.UpsamplingHz))));
            for (var step = 1; step <= steps; step++)
                Append(times[i - 1] + (times[i] - times[i - 1]) * step / steps);
        }
        if (stroke.BrushProfile == PenStyle.Profile && stroke.PenStyle != null)
            return ModelStyle(stroke, modeledInputs, times, window);
        var result = new List<Sample>(modeledInputs.Count);
        left = right = 0;
        var speedResponse = 0d;
        var directionResponse = 1d;
        Point ModeledPositionAt(double time, int index)
        {
            if (index == modeledInputs.Count - 1) return modeledInputs[index].Position;
            var a = modeledInputs[index]; var b = modeledInputs[index + 1];
            return a.Position + (b.Position - a.Position) * ((time - a.Time) / (b.Time - a.Time));
        }
        for (var i = 0; i < modeledInputs.Count; i++)
        {
            var input = modeledInputs[i];
            var start = Math.Max(0, input.Time - window / 2d);
            var end = Math.Min(times[^1], input.Time + window / 2d);
            while (left + 1 < modeledInputs.Count && modeledInputs[left + 1].Time <= start) left++;
            while (right + 1 < modeledInputs.Count && modeledInputs[right + 1].Time <= end) right++;
            var delta = ModeledPositionAt(end, right) - ModeledPositionAt(start, left);
            var speed = end > start ? SpeedThinning(delta.Length * unitCm * 1000 / (end - start)) : 0;
            var direction = delta.Length > 1e-8 ? DirectionMultiplier(delta.Y / delta.Length) : directionResponse;
            var alpha = i == 0 ? 1 : 1 - Math.Exp(-(input.Time - modeledInputs[i - 1].Time) / FountainV2Contract.ResponseMilliseconds);
            speedResponse += (speed - speedResponse) * alpha;
            directionResponse += (direction - directionResponse) * alpha;
            // Speed/direction targets have 25 ms damping; pressure has only input-window smoothing.
            var noSpeed = WidthMultiplier(input.Pressure, stroke.PressurePercent, 0, 0, 0);
            var flowEnd = Math.Max(FountainV2Contract.MinimumSpeedMultiplier, 1 - FountainV2Contract.ThinningCap * Math.Clamp(stroke.InkFlowPercent / 100d, 0, 2));
            result.Add(new Sample(input.Position, input.Pressure, input.Time, stroke.WidthMm * UnitsPerMm * noSpeed *
                (1 - (1 - flowEnd) * speedResponse / FountainV2Contract.ThinningCap) * directionResponse));
        }
        return result;
    }

    private static IReadOnlyList<Sample> ModelStyle(StrokeRecord stroke, List<(Point Position, double Pressure, double Time)> inputs, double[] rawTimes, int window)
    {
        var style = stroke.PenStyle!;
        var result = new List<Sample>(inputs.Count);
        var distances = new double[inputs.Count];
        for (var i = 1; i < inputs.Count; i++) distances[i] = distances[i - 1] + (inputs[i].Position - inputs[i - 1].Position).Length;
        var unitCm = stroke.StrokeUnitLengthCm > 0 ? stroke.StrokeUnitLengthCm : 2.54 / 96;
        var left = 0; var right = 0; var raw = 0;
        double pressure = 1, speed = 1, direction = 1, turn = 1, headResponse = 1, tailResponse = 1;
        var previousVelocity = new Vector();
        Point At(double time, int index)
        {
            if (index == inputs.Count - 1) return inputs[index].Position;
            var a = inputs[index]; var b = inputs[index + 1];
            return a.Position + (b.Position - a.Position) * ((time - a.Time) / (b.Time - a.Time));
        }
        static double Response(double previous, double target, double dt, int ms, bool first) => first || ms <= 0 ? target : previous + (target - previous) * (1 - Math.Exp(-dt / ms));
        for (var i = 0; i < inputs.Count; i++)
        {
            var input = inputs[i];
            var dt = i == 0 ? 1 : input.Time - inputs[i - 1].Time;
            var start = Math.Max(0, input.Time - window / 2d);
            var end = Math.Min(rawTimes[^1], input.Time + window / 2d);
            while (left + 1 < inputs.Count && inputs[left + 1].Time <= start) left++;
            while (right + 1 < inputs.Count && inputs[right + 1].Time <= end) right++;
            var delta = At(end, right) - At(start, left);
            var velocity = end > start ? delta * (unitCm * 1000 / (end - start)) : previousVelocity;
            var acceleration = (velocity - previousVelocity) * (1000 / dt);
            var lateral = velocity.Length > 1e-8 ? (velocity.X * acceleration.Y - velocity.Y * acceleration.X) / velocity.Length : 0;
            var dy = delta.Length > 1e-8 ? delta.Y / delta.Length : 0;
            pressure = Response(pressure, style.PressureEnabled ? PenStyle.Evaluate(style.PressureCurve, input.Pressure) : 1, dt, style.PressureResponseMs, i == 0);
            speed = Response(speed, style.SpeedEnabled ? PenStyle.Evaluate(style.SpeedCurve, velocity.Length) : 1, dt, style.SpeedResponseMs, i == 0);
            direction = Response(direction, style.DirectionEnabled ? 1 + style.DirectionAmount * Math.Clamp((dy - style.DirectionMinimum) / (style.DirectionMaximum - style.DirectionMinimum), 0, 1) : 1, dt, style.DirectionResponseMs, i == 0);
            turn = Response(turn, style.TurnEnabled ? 1 + style.TurnAmount * Math.Clamp((-lateral - style.TurnMinimum) / (style.TurnMaximum - style.TurnMinimum), 0, 1) : 1, dt, style.TurnResponseMs, i == 0);
            while (raw + 1 < rawTimes.Length && rawTimes[raw + 1] <= input.Time) raw++;
            var p = stroke.Points[raw];
            // WPF stores degrees from vertical on each axis; Android Ink consumes total radians.
            var tx = Math.Tan(Math.Clamp(p.TiltX, -89, 89) * Math.PI / 180);
            var ty = Math.Tan(Math.Clamp(p.TiltY, -89, 89) * Math.PI / 180);
            var tilt = p.TiltRadians is { } radians && float.IsFinite(radians) ? Math.Clamp(radians, 0, Math.PI / 2) : Math.Atan(Math.Sqrt(tx * tx + ty * ty));
            var tiltWidth = style.TiltEnabled ? 1 + (style.TiltWidth - 1) * Math.Clamp(tilt / style.TiltMaximumRadians, 0, 1) : 1;
            var head = style.StartEnabled ? style.StartMinimum + (1 - style.StartMinimum) * Math.Clamp(distances[i] / (style.StartLengthMm * UnitsPerMm), 0, 1) : 1;
            var tail = style.EndEnabled ? style.EndMinimum + (1 - style.EndMinimum) * Math.Clamp((distances[^1] - distances[i]) / (style.EndLengthMm * UnitsPerMm), 0, 1) : 1;
            headResponse = Response(headResponse, head, dt, style.StartResponseMs, i == 0);
            tailResponse = Response(tailResponse, tail, dt, style.EndResponseMs, i == 0);
            result.Add(new(input.Position, input.Pressure, input.Time, stroke.WidthMm * UnitsPerMm * pressure * speed * direction * turn * tiltWidth * headResponse * tailResponse));
            previousVelocity = velocity;
        }
        return result;
    }

    // The stored outline is a union of discs and their tangent hulls. 0.7.21-0.7.33 wrote one disc per
    // 180 Hz modeled sample with full double precision (~600 bytes per sample); dense pages reached
    // 358 MB and Android could not load them. Samples whose disc is reproduced by interpolation
    // within OutlineTolerance are dropped, and points are written on a 0.01 DIP grid (2.6 µm).
    public const double OutlineTolerance = .02;
    private const double OutlineGrid = 100;
    private const double CircleK = .5522847498307936;

    private readonly record struct OutlineFigure(Point Start, Point[] Points, bool Curves);

    /// <summary>Indexes of samples kept by Douglas-Peucker on (x, y, radius).</summary>
    internal static List<int> OutlineSampleIndexes(IReadOnlyList<Sample> samples, double tolerance = OutlineTolerance)
    {
        var count = samples.Count;
        if (count <= 2) return Enumerable.Range(0, count).ToList();
        var keep = new bool[count];
        keep[0] = keep[count - 1] = true;
        var stack = new Stack<(int Start, int End)>();
        stack.Push((0, count - 1));
        while (stack.Count > 0)
        {
            var (start, end) = stack.Pop();
            if (end - start < 2) continue;
            var a = samples[start]; var b = samples[end];
            var chord = b.Position - a.Position;
            var length2 = chord.LengthSquared;
            var worst = -1d; var index = -1;
            for (var i = start + 1; i < end; i++)
            {
                var s = samples[i];
                var t = length2 <= 1e-16 ? 0 : Math.Clamp(((s.Position - a.Position) * chord) / length2, 0, 1);
                // Boundary deviation of the disc against the interpolated disc at the same position.
                var error = (s.Position - (a.Position + chord * t)).Length + Math.Abs(s.Width - (a.Width + (b.Width - a.Width) * t)) / 2;
                if (error > worst) { worst = error; index = i; }
            }
            if (worst <= tolerance) continue;
            keep[index] = true;
            stack.Push((start, index));
            stack.Push((index, end));
        }
        var result = new List<int>();
        for (var i = 0; i < count; i++) if (keep[i]) result.Add(i);
        return result;
    }

    private static double Grid(double value) => Math.Round(value * OutlineGrid, MidpointRounding.AwayFromZero) / OutlineGrid;
    private static Point Grid(Point point) => new(Grid(point.X), Grid(point.Y));

    private static List<OutlineFigure> OutlineFigures(StrokeRecord stroke) => OutlineFigures(Model(stroke));

    private static List<OutlineFigure> OutlineFigures(IReadOnlyList<Sample> all)
    {
        var samples = OutlineSampleIndexes(all).Select(index => all[index]).ToList();
        var figures = new List<OutlineFigure>(samples.Count * 2);
        for (var i = 0; i < samples.Count; i++)
        {
            var sample = samples[i];
            var radius = sample.Width / 2;
            var p = sample.Position;
            if (radius > 1e-8)
            {
                // Cubic circles are supported by all three shared-outline readers (iOS lacks A).
                const double k = CircleK;
                figures.Add(new(Grid(p + new Vector(radius, 0)),
                [
                    Grid(p + new Vector(radius, radius * k)), Grid(p + new Vector(radius * k, radius)), Grid(p + new Vector(0, radius)),
                    Grid(p + new Vector(-radius * k, radius)), Grid(p + new Vector(-radius, radius * k)), Grid(p + new Vector(-radius, 0)),
                    Grid(p + new Vector(-radius, -radius * k)), Grid(p + new Vector(-radius * k, -radius)), Grid(p + new Vector(0, -radius)),
                    Grid(p + new Vector(radius * k, -radius)), Grid(p + new Vector(radius, -radius * k)), Grid(p + new Vector(radius, 0)),
                ], true));
            }
            if (i == 0) continue;
            var previous = samples[i - 1];
            var previousRadius = previous.Width / 2;
            var delta = p - previous.Position;
            var distance = delta.Length;
            // A disc inside its neighbour needs no hull; a zero-length hull has no area.
            if (distance <= 1e-8 || distance <= Math.Abs(previousRadius - radius) || previousRadius + radius <= 1e-8) continue;
            var along = delta / distance;
            var normal = new Vector(along.Y, -along.X);
            // Outer tangents of both discs, so dropped intermediate samples are still covered exactly.
            // Equal radii reduce to the previous normal-offset quad and keep its winding (same as discs).
            var cos = (previousRadius - radius) / distance;
            var sin = Math.Sqrt(Math.Max(0, 1 - cos * cos));
            var first = along * cos + normal * sin;
            var second = along * cos - normal * sin;
            figures.Add(new(Grid(previous.Position + first * previousRadius),
                [Grid(p + first * radius), Grid(p + second * radius), Grid(previous.Position + second * previousRadius)], false));
        }
        return figures;
    }

    private static StreamGeometry Geometry(IReadOnlyList<OutlineFigure> figures)
    {
        var geometry = new StreamGeometry { FillRule = FillRule.Nonzero };
        using (var context = geometry.Open())
        {
            foreach (var figure in figures)
            {
                context.BeginFigure(figure.Start, true, true);
                if (figure.Curves)
                    for (var i = 0; i < figure.Points.Length; i += 3)
                        context.BezierTo(figure.Points[i], figure.Points[i + 1], figure.Points[i + 2], true, false);
                else
                    foreach (var point in figure.Points) context.LineTo(point, true, false);
            }
        }
        geometry.Freeze();
        return geometry;
    }

    public static StreamGeometry CreateGeometry(StrokeRecord stroke) => Geometry(OutlineFigures(stroke));

    private static void AppendNumber(System.Text.StringBuilder text, double value)
    {
        value = Grid(value);
        if (value == 0) { text.Append('0'); return; }
        var formatted = value.ToString("0.##", CultureInfo.InvariantCulture);
        if (formatted.StartsWith("0.", StringComparison.Ordinal)) text.Append(formatted, 1, formatted.Length - 1);
        else if (formatted.StartsWith("-0.", StringComparison.Ordinal)) text.Append('-').Append(formatted, 2, formatted.Length - 2);
        else text.Append(formatted);
    }

    /// <summary>Compact shared outline: absolute figure starts, relative c/l segments, 0.01 DIP grid.</summary>
    internal static string OutlineText(StrokeRecord stroke) => OutlineText(OutlineFigures(stroke));

    private static string OutlineText(IReadOnlyList<OutlineFigure> figures)
    {
        var text = new System.Text.StringBuilder(16 + figures.Count * 64);
        text.Append("F1");
        foreach (var figure in figures)
        {
            text.Append('M'); AppendNumber(text, figure.Start.X); text.Append(','); AppendNumber(text, figure.Start.Y);
            text.Append(figure.Curves ? 'c' : 'l');
            for (var i = 0; i < figure.Points.Length; i++)
            {
                if (i > 0) text.Append(' ');
                // All three points of a c segment are relative to that segment's start; l is chained.
                var previous = figure.Curves ? i - i % 3 - 1 : i - 1;
                var origin = previous < 0 ? figure.Start : figure.Points[previous];
                AppendNumber(text, figure.Points[i].X - origin.X); text.Append(',');
                AppendNumber(text, figure.Points[i].Y - origin.Y);
            }
            text.Append('z');
        }
        return text.ToString();
    }

    public static void StoreOutline(StrokeRecord stroke)
    {
        var figures = OutlineFigures(stroke);
        var geometry = Geometry(figures);
        stroke.VectorPath = OutlineText(figures);
        var bounds = geometry.Bounds;
        stroke.VectorBounds = bounds.IsEmpty ? null : [bounds.Left, bounds.Top, bounds.Right, bounds.Bottom];
        stroke.VectorTransform = [1, 0, 0, 1, 0, 0];
        stroke.InvalidateBounds();
    }

    /// <summary>
    /// The 0.7.21-0.7.33 outline text (one disc per modeled sample, full precision). Used only to prove
    /// that a stored outline is still exactly this stroke's own generated outline before compacting it.
    /// </summary>
    internal static string LegacyOutlineText(StrokeRecord stroke) => LegacyOutlineText(Model(stroke));

    /// <summary>
    /// Re-encodes a stored 0.7.21-0.7.33 outline in the compact form, in the outline's own coordinate
    /// space (vectorTransform is unchanged). Returns null unless the text is exactly a legacy disc/hull
    /// outline: the recovered discs must regenerate every stored number within 1e-9 DIP.
    /// </summary>
    internal static (string Text, Rect Bounds)? CompactLegacyOutline(string text)
    {
        if (!text.StartsWith("F1M", StringComparison.Ordinal) || text.Contains('c') || text.Contains('l')) return null;
        var stored = LegacyNumbers(text);
        var samples = new List<Sample>();
        foreach (var figure in text[2..].Split('M', StringSplitOptions.RemoveEmptyEntries))
        {
            if (!figure.Contains('C')) continue;
            var n = LegacyNumbers(figure);
            if (n.Count != 26) return null;
            // Disc start is p + (r, 0); its third point is p + (0, r).
            var x = n[6]; var y = n[1]; var radius = n[0] - x;
            if (!(radius > 1e-8)) return null;
            samples.Add(new Sample(new Point(x, y), 1, samples.Count, radius * 2));
        }
        if (samples.Count == 0) return null;
        var regenerated = LegacyNumbers(LegacyOutlineText(samples));
        if (regenerated.Count != stored.Count) return null;
        for (var i = 0; i < stored.Count; i++)
            if (Math.Abs(stored[i] - regenerated[i]) > 1e-9) return null;
        var figures = OutlineFigures(samples);
        return (OutlineText(figures), Geometry(figures).Bounds);
    }

    private static readonly System.Text.RegularExpressions.Regex LegacyNumber =
        new(@"-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", System.Text.RegularExpressions.RegexOptions.Compiled | System.Text.RegularExpressions.RegexOptions.CultureInvariant);

    private static List<double> LegacyNumbers(string text)
    {
        var values = new List<double>();
        foreach (System.Text.RegularExpressions.Match match in LegacyNumber.Matches(text))
            values.Add(double.Parse(match.ValueSpan, NumberStyles.Float, CultureInfo.InvariantCulture));
        return values;
    }

    private static string LegacyOutlineText(IReadOnlyList<Sample> samples)
    {
        var geometry = new StreamGeometry { FillRule = FillRule.Nonzero };
        using (var context = geometry.Open())
        {
            for (var i = 0; i < samples.Count; i++)
            {
                var sample = samples[i];
                var radius = sample.Width / 2;
                if (radius <= 1e-8) continue;
                const double k = CircleK;
                var p = sample.Position;
                context.BeginFigure(p + new Vector(radius, 0), true, true);
                context.BezierTo(p + new Vector(radius, radius * k), p + new Vector(radius * k, radius), p + new Vector(0, radius), true, false);
                context.BezierTo(p + new Vector(-radius * k, radius), p + new Vector(-radius, radius * k), p + new Vector(-radius, 0), true, false);
                context.BezierTo(p + new Vector(-radius, -radius * k), p + new Vector(-radius * k, -radius), p + new Vector(0, -radius), true, false);
                context.BezierTo(p + new Vector(radius * k, -radius), p + new Vector(radius, -radius * k), p + new Vector(radius, 0), true, false);
                if (i == 0) continue;
                var previous = samples[i - 1];
                var delta = p - previous.Position;
                if (delta.Length <= 1e-8) continue;
                var normal = new Vector(delta.Y, -delta.X) / delta.Length;
                context.BeginFigure(previous.Position + normal * (previous.Width / 2), true, true);
                context.LineTo(p + normal * radius, true, false);
                context.LineTo(p - normal * radius, true, false);
                context.LineTo(previous.Position - normal * (previous.Width / 2), true, false);
            }
        }
        geometry.Freeze();
        return geometry.ToString(CultureInfo.InvariantCulture);
    }
}
