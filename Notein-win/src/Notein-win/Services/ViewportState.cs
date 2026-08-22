using System.Windows;
using System.Windows.Media;
using Notein_win.Models;

namespace Notein_win.Services;

public class ViewportState
{
    public float MinScale { get; } = 0.5f;
    public float MaxScale { get; } = 8.0f;

    public float Scale { get; private set; } = 1.0f;
    public float TranslationX { get; private set; } = 0.0f;
    public float TranslationY { get; private set; } = 0.0f;

    public ViewportState(float minScale = 0.5f, float maxScale = 8.0f)
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
