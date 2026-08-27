using Notein_win.Models;

namespace Notein_win.Services;

public static class PressureEraserPolicy
{
    public static bool CanStartCandidate(
        bool isStylus,
        bool armedAfterCompletedStroke,
        DrawingToolKind toolKind,
        bool enabled,
        float pressure,
        int thresholdPercent) =>
        isStylus &&
        armedAfterCompletedStroke &&
        toolKind is DrawingToolKind.Pen or DrawingToolKind.Highlighter &&
        enabled &&
        pressure * 100 >= thresholdPercent;

    public static bool ShouldActivate(
        bool candidateActive,
        bool hasTemporaryStroke,
        double elapsedMilliseconds,
        int dwellMilliseconds) =>
        candidateActive && hasTemporaryStroke && elapsedMilliseconds >= dwellMilliseconds;
}
