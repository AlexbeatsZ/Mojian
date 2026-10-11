# Windows writing performance and durability

Last verified: 2026-10-08

Read before changing Windows wet/dry rendering, pointer feedback, undo history,
autosave scheduling, recovery, or same-page split document ownership.

## Incident and measured baseline

The user reported that a small handwritten page became very slow, that the mouse
remained smooth while the pen lagged inside Mojian, and that earlier Windows notes
had disappeared after an update. The active page had 584 strokes / 14,396 input
points, no inserted objects, and about 1.53 MB of compact JSON.

The old WPF renderer recreated a `Pen` / `DrawLine` for every segment of every
visible freehand stroke whenever the canvas invalidated, including pen hover.
Each new stroke also serialized the entire page for Undo; each completed stroke
synchronously serialized/wrote the page and library on the UI dispatcher.

A read-only copy of the reported page measured 42.90 ms per repeated drawing
command recording, 15.11 ms per whole-page snapshot, and 18.65 ms per synchronous
page save. These are code-path CPU times, not end-to-end nib-to-photon latency.

Two independent storage faults were reproduced with failing regressions:

- Same-page split slots loaded separate mutable documents; right-side new ink was
  saved, then `SaveAll()` selected the stale left document and overwrote it.
- A damaged page was renamed and returned as an empty document, with no previous
  successful revision available. Read/permission errors were also treated as
  corruption. Preferences errors could silently fall back to another workspace.

These are confirmed defects, not proof of the user's particular past loss.
The earlier missing page/date has not been identified. The user explicitly declined
further recovery on 2026-10-08; old workspace/recovery folders remain intact.

### Follow-up: zoomed-out pen motion

The user verified improvement at high zoom but still reported severe lag when
zoomed out. The previous 0.5–1.4 ms result measured drawing command recording,
not rasterization, so it was insufficient evidence for the remaining symptom.
The old frozen groups still contained approximately 14,000 round-capped line
commands. More strokes enter the viewport at low zoom, leaving substantial
compositor/raster work even without rebuilding those commands.

Actual 1400×1000 software `RenderTargetBitmap` snapshots of the reported page
measured 86/68/47/31 ms at 50/100/200/400% in the first full-canvas run. The final
same-run retained-reference comparison measured approximately 56/50/38/25 ms
for the old per-segment path and 22/25/24/19 ms for the new renderer. Both figures
include software snapshot overhead; neither is a physical pen latency measurement.

`FountainStrokeGeometry` fills each stroke as one nonzero-winding stream geometry
containing the union of the same pressure-sized, smoothed, round-ended segments.
It retains the input points, brush width/pressure/flow/taper equations and editable
vectors. Anti-aliasing at inner overlaps can differ from repeated line overdraw;
the geometric silhouette is tested against independent point-to-segment distances.
Shapes, highlighters and imported native contours retain their existing renderers.

Ordinary authored pages have separate completed-scene, active-ink, editor and
pointer/selection visuals. Moving the nib updates only the active stroke and
feedback, then lift hands the frozen drawing to the scene. Source layers or tags
can cover ink, so those pages keep the canonical composite painter order. Native
imports are never sorted again for every raw pen sample to decide overlay eligibility.

The 50% regression appends 30 samples without another completed-scene render;
wet-to-dry pixel differences stay within two channel values. Undo and 64x zoom,
pressure erasing and source-layer occlusion also pass. A WPF `BitmapCache` and an
explicit screen bitmap were evaluated but rejected because they changed edge
pixels at handoff. The delivered scene remains vector-backed, with no scaled-up
page bitmap or additional synchronous raster task on the input dispatcher.

### Follow-up: sluggish panorama Hand / middle-drag pan (2026-10-12)

The user reported that the Windows Hand felt laggy. Every pan sample called
`InvalidateVisual()`, so WPF re-recorded and re-rasterized the whole visible
scene per sample; WPF has no automatic layer cache, so translating the vector
scene still re-rasterizes it. Real-window GPU measurements on the live workspace
(1400×1000, 240 Hz panel, one pan step per `CompositionTarget.Rendering`):

| Page | Zoom | Per-sample redraw | Cached pan |
|---|---|---|---|
| 1,105 strokes | 50% | 22 ms median (≈44 fps) | 4.1 ms |
| 3,096 strokes | 50% | 31 ms median (≈30 fps) | 4.1 ms |
| 3,096 strokes | 100% | 8–10 ms | 4.2 ms |
| 10,613 strokes | 50% | 13 ms | 1.5–4.5 ms |

Contract: while `_isPanning` (pen Hand, mouse or stylus middle navigation),
`SceneLayer` temporarily gets a `BitmapCache`, `ClipToBounds=false` and a
`TranslateTransform`. The scene renders half a view of overscan on every side,
clipped to that rectangle so the cache stays bounded, at the base position
(`DrawScreenScene` subtracts the accumulated offset, so any re-render during the
gesture stays aligned). Samples only move the transform; when the offset exceeds
half the view the scene re-renders once around the current viewport. Zoom
gestures end the cache first; release, cancel and transient-state clearing
restore the vector scene. The mouse-move path must not invalidate the whole
canvas while the cache is active, or it rasterizes the cache again per sample.

The moving bitmap differs from the vector scene only in edge anti-aliasing
(mean ≈1 per channel in the regression; a one-pixel misplacement measures
several times higher). Release returns to the exact vector pixels. This differs
from the rejected wet/dry `BitmapCache`: nothing is cached across a static handoff.
`HandPanMovesCachedSceneWithoutPerSampleRedrawAndMatchesVectorPixels` covers no
per-sample scene builds, one overscan re-render, alignment and release pixels.
Two-finger touch manipulation still redraws per delta because it also scales.

## Reference investigation

Notein's earlier verified 2.0.10.0 native Ink library exactly matched AndroidX Ink
1.0.0. For this investigation the official `ink-authoring-android:1.0.0` source
artifact was read, especially `CanvasInProgressStrokesRenderHelperV29`,
`InProgressStrokesManager`, and `FrontBufferToHwuiHandoffV29`:

- Rendering uses a separate render thread and a front-buffered layer.
- `prepareToDrawInModifiedRegion` clips drawing/clearing to the changed region.
- Completed strokes hand off to HWUI through an explicit synchronized path.
- Finished `Stroke` geometry is immutable and reusable; UI does not need to
  recompute all old brush geometry for the next pen sample.

Source artifact: https://dl.google.com/dl/android/maven2/androidx/ink/ink-authoring-android/1.0.0/ink-authoring-android-1.0.0-sources.jar
Public overview: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-modules

The current tablet was not connected. Protected Notein app-specific scheduling,
Undo, database transactions and saving algorithms were not recovered or claimed.

Read-only native inspection of local OneNote `onmain.dll` 16.0.20430.20092 found
Direct2D/D3D11 imports, wet-ink updates, dry-stroke processing, rendering-bound and
render-cache metadata, an `IAirSpaceInkInput Rendering Thread`, and autosave
interval names. A narrow reference/disassembly check found the rendering-thread
name passed into an Mso client initialization call, and a separate dry-stroke
processing/telemetry route. These support separation of wet input, dry content and
background work; they do not reveal the complete proprietary implementation or
the exact cache/saving policy.

Microsoft's public WPF implementation makes the principle explicit:
https://learn.microsoft.com/en-us/dotnet/desktop/wpf/advanced/custom-rendering-ink
https://github.com/dotnet/wpf/blob/main/src/Microsoft.DotNet.Wpf/src/PresentationCore/System/Windows/Input/Stylus/Common/DynamicRenderer.cs

## Implemented Windows contract

- Pen-dot and Insert Space hover feedback own a separate non-interactive visual.
  Hover updates this visual without invalidating/rebuilding the document. Raw and
  promoted stylus streams retain their existing ownership/coordinate rules.
- Completed freehand/highlighter/shape strokes retain frozen document-space
  `DrawingGroup` objects. Only the active stroke is rebuilt while writing. Source
  imported vector outlines retain their existing geometry cache. Zoom remains
  vector rendering, with no page raster flattening.
- Fountain freehand uses one filled geometry per stroke instead of per-sample
  stroked lines. Ordinary wet ink owns a separate visual and reuses the completed
  scene; source-layer/tagged pages preserve their canonical occlusion path.
- Cache identity includes point version/count, width, brush parameters, color,
  theme and export mode. Point replacement/transformation invalidates the version;
  weak stroke ownership does not retain deleted page content indefinitely.
- Ordinary appended ink uses an ID-based undo entry. Complex document edits keep
  snapshots; pressure activation promotes the pending append entry before erasing
  existing content. Undo capacity keeps the newest 80 entries.
- Completed edits capture independent model copies and queue JSON/page/library
  writes on one worker. Pending jobs for the same page coalesce; explicit sequence
  numbers preserve library order across pages. Dictionary slot order is not a
  reliable submission order after replacing/removing keys.
- Navigation, deletion, workspace changes, explicit Save, export and Close flush
  pending work before writing synchronously. Failed jobs remain available for
  retry. Close failures cancel closing and report the error; autosave failures show
  a compact error message without a permanent canvas status panel.
- A same-page split slot shares the canonical in-memory document and invalidates
  its peer after content changes. A second desktop process activates the existing
  window instead of opening an independent writer.
- Atomic writes flush the temporary file to disk before `File.Replace`, retaining
  one `.previous` revision beside each written JSON. Corrupt JSON is copied aside
  and recovers from a valid previous revision; absent/unusable recovery stops
  loading. IO/permission errors propagate without renaming data or returning empty
  models. Explicit page deletion also deletes its previous revision.
- The schema and `.mjn` exchange format do not change.

## Platform scope and verification

This incident was explicitly identified as Windows. Android was inspected: it
already uses AndroidX wet/dry authoring and one live canvas with page sessions,
so it does not have the WPF per-segment replay or duplicate live split document
routes. Android ordinary freehand still has the separately documented session
durability boundary; this Windows repair does not claim to fix or deliver that
different storage model. Android generated shapes/assets are unaffected.

The reported page's retained drawing path now measures about 0.5–1.4 ms per repeat
in these runs. Capturing an independent 14,396-point model copy takes about 2 ms;
JSON/durable disk work executes on the background worker. First page rendering
still builds the completed-stroke cache. Physical pen feel remains user acceptance.

`WritingReliabilityTests` covers real nib-overlay raster movement without a
document redraw, original-versus-cached pixel equality for pen/highlighter/shape,
cache invalidation, Undo/Redo/pressure rollback/history retention, split overwrite,
serialized/coalesced background jobs, failed-write retry and temporary-file
cleanup, read locks, corrupt current/previous recovery and real window Close flush.
`MOJIAN_WRITING_PAGE_JSON` runs the optional read-only real-page timing check.
It now measures actual software rasterization at 50/100/200/400%, alongside the
retained old renderer. `MOJIAN_WRITING_RENDER_DIRECTORY` optionally writes the
100% visual comparison to a caller-selected temporary directory.
