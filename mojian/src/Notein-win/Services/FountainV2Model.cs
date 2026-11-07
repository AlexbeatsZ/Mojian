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
        [(0, 0), (1, 0), (2, .08), (4, .15), (8, .23), (16, .46), (32, .69), (64, .85)];

    public static int StabilityWindowMillis(int percent)
    {
        var p = Math.Clamp(percent, 0, 100);
        return Math.Max(1, (int)Math.Floor((p <= 50 ? .4 * p : 20 + 1.6 * (p - 50)) + .5));
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
        return .85;
    }

    public static double DirectionMultiplier(double directionY) => 1 + .17 * Math.Clamp((directionY - .45) / .2, 0, 1);

    public static double WidthMultiplier(double pressure, int pressurePercent, double speedCmPerSecond, int flowPercent, double directionY)
    {
        var gamma = Math.Clamp(pressurePercent / 100d, 0, 4);
        var p = Math.Clamp(pressure, 0, 1);
        // AndroidX uses the same 16-segment pressure curve, including its p^0 contact step.
        var part = Math.Min(15, (int)(p * 16));
        var pressureValue = gamma == 0 ? 1 :
            Math.Pow(part / 16d, gamma) * (1 - (p * 16 - part)) +
            Math.Pow((part + 1) / 16d, gamma) * (p * 16 - part);
        // Match Android's clamped endpoint (important above 100% flow), not a negative width.
        var end = Math.Max(.05, 1 - .85 * Math.Clamp(flowPercent / 100d, 0, 2));
        return 1.4 * pressureValue * (1 - (1 - end) * SpeedThinning(speedCmPerSecond) / .85) * DirectionMultiplier(directionY);
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
            var steps = (int)Math.Min(100, Math.Ceiling((times[i] - times[i - 1]) / (1000d / 180)));
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
            var alpha = i == 0 ? 1 : 1 - Math.Exp(-(input.Time - modeledInputs[i - 1].Time) / 25);
            speedResponse += (speed - speedResponse) * alpha;
            directionResponse += (direction - directionResponse) * alpha;
            // Speed/direction targets have 25 ms damping; pressure has only input-window smoothing.
            var noSpeed = WidthMultiplier(input.Pressure, stroke.PressurePercent, 0, 0, 0);
            var flowEnd = Math.Max(.05, 1 - .85 * Math.Clamp(stroke.InkFlowPercent / 100d, 0, 2));
            result.Add(new Sample(input.Position, input.Pressure, input.Time, stroke.WidthMm * UnitsPerMm * noSpeed *
                (1 - (1 - flowEnd) * speedResponse / .85) * directionResponse));
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
        double pressure = 1, speed = 1, direction = 1, turn = 1;
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
            direction = Response(direction, style.DirectionEnabled ? 1 + style.DirectionAmount * Math.Clamp((dy - .45) / .2, 0, 1) : 1, dt, style.DirectionResponseMs, i == 0);
            turn = Response(turn, style.TurnEnabled ? 1 + style.TurnAmount * Math.Clamp((-lateral - 80) / 150, 0, 1) : 1, dt, 25, i == 0);
            while (raw + 1 < rawTimes.Length && rawTimes[raw + 1] <= input.Time) raw++;
            var p = stroke.Points[raw];
            // WPF stores degrees from vertical on each axis; Android Ink consumes total radians.
            var tx = Math.Tan(Math.Clamp(p.TiltX, -89, 89) * Math.PI / 180);
            var ty = Math.Tan(Math.Clamp(p.TiltY, -89, 89) * Math.PI / 180);
            var tilt = p.TiltRadians is { } radians && float.IsFinite(radians) ? Math.Clamp(radians, 0, Math.PI / 2) : Math.Atan(Math.Sqrt(tx * tx + ty * ty));
            var tiltWidth = style.TiltEnabled ? 1 + (style.TiltWidth - 1) * Math.Clamp(tilt / (Math.PI / 3), 0, 1) : 1;
            var head = style.StartEnabled ? style.StartMinimum + (1 - style.StartMinimum) * Math.Clamp(distances[i] / (style.StartLengthMm * UnitsPerMm), 0, 1) : 1;
            var tail = style.EndEnabled ? style.EndMinimum + (1 - style.EndMinimum) * Math.Clamp((distances[^1] - distances[i]) / (style.EndLengthMm * UnitsPerMm), 0, 1) : 1;
            result.Add(new(input.Position, input.Pressure, input.Time, stroke.WidthMm * UnitsPerMm * pressure * speed * direction * turn * tiltWidth * head * tail));
            previousVelocity = velocity;
        }
        return result;
    }

    public static StreamGeometry CreateGeometry(StrokeRecord stroke)
    {
        var samples = Model(stroke);
        var geometry = new StreamGeometry { FillRule = FillRule.Nonzero };
        using (var context = geometry.Open())
        {
            for (var i = 0; i < samples.Count; i++)
            {
                var sample = samples[i];
                var radius = sample.Width / 2;
                if (radius <= 1e-8) continue;
                // Cubic circles are supported by all three shared-outline readers (iOS lacks A).
                const double k = .5522847498307936;
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
                // Same clockwise winding as the circles, including retraces and self crossings.
                context.BeginFigure(previous.Position + normal * (previous.Width / 2), true, true);
                context.LineTo(p + normal * radius, true, false);
                context.LineTo(p - normal * radius, true, false);
                context.LineTo(previous.Position - normal * (previous.Width / 2), true, false);
            }
        }
        geometry.Freeze();
        return geometry;
    }

    public static void StoreOutline(StrokeRecord stroke)
    {
        var geometry = CreateGeometry(stroke);
        stroke.VectorPath = geometry.ToString(CultureInfo.InvariantCulture);
        var bounds = geometry.Bounds;
        stroke.VectorBounds = bounds.IsEmpty ? null : [bounds.Left, bounds.Top, bounds.Right, bounds.Bottom];
        stroke.VectorTransform = [1, 0, 0, 1, 0, 0];
        stroke.InvalidateBounds();
    }
}
