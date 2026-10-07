using System.Windows;
using System.Windows.Media;
using Notein_win.Models;

namespace Notein_win.Services;

public class ViewportState
{
    public const float DefaultMinScale = 0.5f;
    public const float DefaultMaxScale = 64f;
    public static IReadOnlyList<int> ZoomPresets { get; } = Array.AsReadOnly(new[] { 50, 75, 100, 125, 150, 200, 400, 800, 1600, 3200, 6400 });

    public float MinScale { get; }
    public float MaxScale { get; }

    public float Scale { get; private set; } = 1.0f;
    public float TranslationX { get; private set; } = 0.0f;
    public float TranslationY { get; private set; } = 0.0f;

    public ViewportState(float minScale = DefaultMinScale, float maxScale = DefaultMaxScale)
    {
        MinScale = minScale;
        MaxScale = maxScale;
    }

    public void ApplyGesture(
        float previousFocusX,
        float previousFocusY,
        float focusX,
        float focusY,
        float requestedScaleFactor)
    {
        var anchorX = (previousFocusX - TranslationX) / Scale;
        var anchorY = (previousFocusY - TranslationY) / Scale;

        var newScale = Math.Clamp(Scale * requestedScaleFactor, MinScale, MaxScale);
        Scale = newScale;
        TranslationX = focusX - anchorX * newScale;
        TranslationY = focusY - anchorY * newScale;
    }

    public void Pan(float deltaX, float deltaY)
    {
        TranslationX += deltaX;
        TranslationY += deltaY;
    }

    public void ZoomAt(float viewX, float viewY, float factor)
    {
        var anchorX = (viewX - TranslationX) / Scale;
        var anchorY = (viewY - TranslationY) / Scale;

        var newScale = Math.Clamp(Scale * factor, MinScale, MaxScale);
        Scale = newScale;
        TranslationX = viewX - anchorX * newScale;
        TranslationY = viewY - anchorY * newScale;
    }

    public static float DiagonalDragZoomFactor(Point previousPoint, Point currentPoint, double sensitivity = 240d)
    {
        if (sensitivity <= 0) throw new ArgumentOutOfRangeException(nameof(sensitivity));
        var downRightDistance = ((currentPoint.X - previousPoint.X) + (currentPoint.Y - previousPoint.Y)) / Math.Sqrt(2);
        return (float)Math.Exp(downRightDistance / sensitivity);
    }

    public void Reset()
    {
        Scale = 1.0f;
        TranslationX = 0.0f;
        TranslationY = 0.0f;
    }

    public void Restore(PageViewport state)
    {
        Scale = Math.Clamp(state.Scale, MinScale, MaxScale);
        TranslationX = state.TranslationX;
        TranslationY = state.TranslationY;
    }

    public void CopyTo(PageViewport state)
    {
        state.Scale = Scale;
        state.TranslationX = TranslationX;
        state.TranslationY = TranslationY;
    }

    public void SetScale(float scale, float anchorX, float anchorY) => ZoomAt(anchorX, anchorY, Math.Clamp(scale, MinScale, MaxScale) / Scale);

    public Point ViewToDocument(double viewX, double viewY) =>
        new((viewX - TranslationX) / Scale, (viewY - TranslationY) / Scale);

    public Point DocumentToView(double docX, double docY) =>
        new(docX * Scale + TranslationX, docY * Scale + TranslationY);

    public Rect DocumentToView(Rect docRect) =>
        new(
            docRect.X * Scale + TranslationX,
            docRect.Y * Scale + TranslationY,
            docRect.Width * Scale,
            docRect.Height * Scale
        );

    public void CenterDocumentPoint(float documentX, float documentY, float viewCenterX, float viewCenterY)
    {
        TranslationX = viewCenterX - documentX * Scale;
        TranslationY = viewCenterY - documentY * Scale;
    }

    public Matrix DocumentToViewMatrix()
    {
        var matrix = Matrix.Identity;
        matrix.Scale(Scale, Scale);
        matrix.Translate(TranslationX, TranslationY);
        return matrix;
    }

    public Matrix ViewToDocumentMatrix()
    {
        var matrix = DocumentToViewMatrix();
        matrix.Invert();
        return matrix;
    }
}
