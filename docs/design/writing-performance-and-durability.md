# Windows writing performance and durability

Last verified: 2026-11-07

Read before changing Windows wet/dry rendering, pointer feedback, undo history,
autosave scheduling, recovery, or same-page split document ownership.

Viewport browsing stays in local settings and cannot mark page content dirty or expand A4
metadata. Canvas content edits mark a pending-content flag before the save debounce; navigation
and shutdown flush it, and incoming refresh must not replace such a document. Queue submission
retains the immutable content snapshot and clears that flag; failed jobs remain in the existing
retry queue. Clean pages are not rewritten on navigation/close. Legacy on-disk viewport data is
preserved separately so subsequent content saves do not publish the current device's view.

## Render-thread cost and outline erasing (0.7.26, 2026-11-07)

Earlier fixes measured UI-thread code paths only. A real-window probe of copies of the user's
pages (3,443 and 1,842 native strokes, a 2,937-stroke layered import; 240 Hz pen samples, the
user's 125% / 240 Hz Intel UHD display) measured per-thread CPU: while writing, WPF's render
thread was busy 5.8-6.0 s of every 6.1 s. WPF re-tessellates every vector drawing that intersects
a dirty region, so the growing wet stroke re-rasterized all old ink beneath it on each frame.

`SceneLayer` now keeps a permanent `BitmapCache` (RenderAtScale 1, no ClearType). The scene root
is clipped to the visible document extent, and the clip/transform are replaced only when they
actually change: a new clip geometry dirties the whole cached layer. Wet ink, pointer feedback and
editors stay outside the cache. The pan cache temporarily replaces it and `EndPanCache` restores
it. Writing render-thread CPU dropped to 1.3-1.8 s with about 3x the frames. On-screen BitBlt
captures at 125% are pixel-identical with and without the cache (no blur, no handoff jump).
An offscreen `RenderTargetBitmap` of the control resamples the cache, so tests comparing vector
handoff pixels through RTB turn it off with the per-canvas `StaticSceneCacheEnabled`. Product
exports render separate visuals and are unaffected.

Layered/imported pages (597 of 621 pages) previously re-recorded the active stroke inside the
scene for each sample. `WetInkIsTopmost` checks once per stroke whether any visible item follows
it in `PageScene.OrderedContent` painter order (or tags exist); new ink normally has the unlisted
top layer, so it uses the wet overlay. Covered ink keeps the canonical in-scene path.

Erasing: the eraser tested fountain-v2/pen-style strokes with `FillContainsWithDetail` against the
stored outline (about 45,000 path characters, one disc per modeled sample plus trapezoids);
8.3-8.5 ms per candidate per sample, plus 0.6 ms to parse the path on first touch.
`StrokeEraserHitTest` tests the same disc/trapezoid union from `FountainV2Model.Model` samples,
cached per stroke `RenderVersion`. On 40 real strokes it took 6.3 ms for 1,494 checks vs 4,303 ms;
the 5 differences were all within 3% of the eraser radius. Imported outlines keep the exact WPF
test (about 0.06 ms). `FillContains || StrokeContains(wide pen)` was rejected: its widening is
slow near the boundary and miter joins over-hit.

Diagnosis lessons: the user's views were zoomed to 8.08x and 1.76x, so test at the stored viewport,
not only at 100%. Sessions attached through RDP or disconnected render differently (tier 0,
throttled frames), so their timings are not valid. Completed WPF testhosts that show live windows
can remain as unkillable one-core spinners ("no running instance") until reboot, which consumes
CPU power shared with the integrated GPU. `LiveInkCostProbe` (`MOJIAN_LIVE_INK_PAGE_JSON`,
optional `MOJIAN_LIVE_INK_VIEWPORT=scale,tx,ty`), `LiveSceneCachePixelProbe` and
`EraseHitCostProbe` (`MOJIAN_ERASE_HIT_PAGE_JSON`) are read-only optional probes.

## Main-window contact must not act as navigation (0.7.24, 2026-11-07)

The user continued to observe intermittent pauses while writing and erasing on 0.7.23. A live
20-second trace confirmed the actual MainWindow `OnLeftCanvasFocus -> FocusCanvas ->
SaveFocusedDocument` route, which isolated NoteCanvas benchmarks omitted. Both canvas borders
handle PreviewMouseDown, including promoted pen contacts. `FocusCanvas` previously flushed the
background page writer, saved settings and rebuilt navigation/appearance even when the requested
slot was already focused. Slow disk, a concurrent page job or the per-workspace file gate can then
hold up the next contact; the stall depends on timing rather than one reproducible pen trajectory.

`FocusCanvas` now returns immediately for the current slot. Actual left/right slot changes keep
their existing durable-save barrier and selection updates. `CanvasFocusPerformanceTests` raises
the real routed preview event through a MainWindow border while the writer is deliberately held:
the old contact waited 1,017 ms for its release; the fixed contact returned in 0.35 ms before release.
Separate tests keep pending ink/file bytes/property notifications untouched on same-slot focus and
prove source ink is durable after a real split-slot change. Do not generalize this early return to
page, notebook, workspace, export or close transitions, which must retain save barriers.

The 20-second live trace had 10 GCs (largest stop 8.53 ms); the following two-minute trace was idle
and captured no GC or writing. A simultaneous UI probe had no foreground samples, so neither is
proof that the user's intermittent stall was captured or that all causes are eliminated. Trace
files stay private under the task temporary directory. Tool-setting work in the first trace came
from real slider input; a speculative binding-refresh change was not included. This remains a
Windows main-window fix; no shared persisted format or Android behavior changes.

## Dense-page Windows erasing and layered writing (0.7.23, 2026-11-07)

The 0.7.21 Windows eraser still serialized the entire page on contact, scanned all strokes for each
sample and invalidated a single page-sized scene visual. A synthetic 3,000-stroke / 240,000-point
page reproduced 353 ms and 110.7 MB of UI-thread allocations on contact; 60 move samples caused
60 complete scene recordings. Frozen DrawingGroups avoided rebuilding geometry, but did not give
the compositor independent dirty bounds for each stroke. Layered/tagged pages also re-recorded and
sorted all visible content for every wet-ink sample to preserve imported occlusion.

`CanvasDocumentScene` now retains a DrawingVisual per visible content item under one document
transform, with frozen stroke drawings from the existing cache. Normal changes reconcile identity
and painter order; appending/removing ink preserves other visual nodes. Wet ink on layered/tagged
pages updates its existing node at its canonical depth, including under source image/text covers.
`PageScene.CompositeContent` supplies both the screen and export painter, including their existing
unlayered text/editor distinction. Pan overscan/temporary bitmap caching is unchanged; all static
ink remains vector-backed. Changing page clears visual ownership; viewport/style changes reconcile
the scene and stroke cache keys include stored outline identity and imported color adaptation.
Paper and image/PDF nodes also retain unchanged drawings across contact/lift. Their signatures
include paper/style/viewport geometry, image transforms/crop, asset path/mtime and PDF decode
revision. The workspace background belongs to the static Grid instead of being re-recorded on
every canvas invalidation. Asset reloads bypass WPF's URI image cache after our mtime key changes.

`EraseGesture` builds a 64-document-unit spatial grid once per contact (large bounds have a bounded
overflow list). Each sample tests nearby candidates with the existing exact circle/outline hit
test. Actual hits remove only their visual nodes. One lazy undo entry owns removed stroke/tag
records and their original list positions; pressure activation uses the same entry after discarding
the temporary wet stroke. No hit means no undo entry, redo invalidation or content save. Cancelling
restores the removed records; lift releases all grid/index storage, leaving only erased records in
history. The gesture marks content pending on its first deletion and still publishes through the
existing save queue at completion; preceding pending edits are flushed even after an empty erase.
Explicit Undo/Redo and complex edits retain the existing whole-document snapshot behavior.

This is a Windows WPF input/compositor repair. Android's separate `DryInkView.eraseAtDocument` and
AndroidX authoring already use local tile invalidation and have no WPF/JSON-on-contact path. No
shared brush, persisted schema, Android build, sync transport or user note content changes here.
Source/native fixture results are recorded in `docs/log/2026-11.md`; physical PC pen feel remains
user acceptance. Software RenderTargetBitmap times are not physical frame-rate measurements.

## Dense save/input follow-up (0.7.21)

Windows JSON encoding and atomic write already run on the save worker. A synthetic 1,900-stroke,
121,600-point, 10,088,486-byte page measured 73–127 ms per save before this change, so the Android
multi-second encoding/freeze failure was not reproduced. Keep Windows' existing atomic write and
external-write guard; do not replace them with HyperOS-specific inode rules.

The UI-thread `CopyForSave` still cloned every point: seven warm runs took 2.7–18.0 ms.
`QueueSaveDocument` now uses a `StrokePointSnapshotCache` keyed by live stroke identity,
`RenderVersion`, point-list identity and count. Unchanged point graphs are reused only by frozen
background save jobs; each stroke's scalar style and vector arrays are copied on every snapshot.
Ordinary `CopyForSave()` continues to make independent editable copies. Every in-place point edit
must call `InvalidateBounds` (already required for drawing/bounds). The cache is weakly keyed and
does not retain inactive documents. Warm copies of the same page measured 0.6–0.9 ms after caching.
`MojianSave` Trace events report UI snapshot, background encode and write durations.

The v2 renderer keeps the existing dry/wet cache separation. Adding one v2 stroke on a 1,900-stroke
synthetic scene builds its wet drawing once and does not rebuild the 1,900 old drawing entries;
stroke finalization measured about 5 ms in the software fixture. These are fixture timings, not
physical pen-to-photon measurements. The Release assembly reports JIT optimization enabled.

In this OMEN SSH test environment the pre-existing `HandPanMovesCachedSceneWithoutPerSampleRedrawAndMatchesVectorPixels`
fails its cached anti-aliasing threshold (mean 1.870, one-row-shift mean 4.546). Repeating with the
original de9a57a canvas gives the same failure. The threshold is retained; this task does not
claim a completely green full suite or a repaired baseline pan-cache issue.

## Recoverable deletion and native-sync publication (2026-10-28)

Notebook/section/page deletion now builds a candidate library without mutating the live model.
`WorkspaceDeletionTransaction` writes a manifest under `.mojian-deletions.previous`, stages
selected page/asset/recovery files, then publishes the candidate index. Refused publication
restores staged files and keeps the original VM model. A rollback failure exposes the recovery
path; do not delete that directory to clear an error. Successful deletion retains recovery files.
On load, restore only missing files referenced by the current library, newest recovery first;
never overwrite an existing file or resurrect an unreferenced page. Empty notebook directories
are removed only after index publication, without recursively deleting surviving files/journals.
This is local recovery, not a transaction with an external Syncthing process.

Repository save, deletion/recovery and incoming publication share the per-root file gate and
`NativeSyncMutationEpoch`. A queued page+library save spans one mutation, and failed retries keep
both snapshots. Outgoing hashing/staging runs without this gate; only final small outbox
publication checks its captured epoch. Concurrent writes invalidate the candidate instead of
making the UI wait for a large-library scan. Native incoming preparation/copies run on a worker;
the host's UI gate executes the short publication and checks repository reload success. Partial
apply or reload refusal locks editing/saving until full local recovery and reload succeed.
See `native-sync.md` and `sync-server/PROTOCOL.md` for the transport/recovery contract.

Failed save retries retain their original submission sequence. Once a newer queued job has
published a library for the same workspace, an older retry may still persist its page but must
not publish its older library snapshot. The UI mutation gate is held while evaluating eligibility
as well as during the operation; reentrant predicates must not sneak in a second mutation.

The first focused deletion/epoch/gate/recovery regressions passed 14/14; the follow-up adds save
retry and reentrancy coverage (18/18 focused). Debug and Release now each pass the full 191/191
tests, including caret and screenshots. All WPF test execution shares one live STA, Application
and Dispatcher; each invocation restores resources and closes leaked windows even on failure.
The assembly framework synchronously shuts down Application and joins its owner at disposal;
otherwise a passing suite can leave an orphan testhost locking product/test DLLs. Non-WPF
test-only runs do not create a Dispatcher just to dispose it.
The caret fixture clears the default sample content before testing a blank click, and asserts
that the temporary editor exists instead of accidentally reading an old insertion anchor.
Earlier UI-harness lifecycle/caret failures and the Release FailFast boundary are historical
evidence in the 2026-10-28 log. Current suite results are recorded in the latest monthly entry.

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
  one `.previous` revision beside each written JSON. Corrupt workspace JSON is copied to
  `.mojian-corrupt.previous/<relative path>.corrupt-*` (never beside pages/assets, where an
  unsupported name halts native sync) and recovers from a valid previous revision;
  absent/unusable recovery stops loading. A missing `library.json` is seeded with the sample
  only in a new empty folder; existing notebooks, a native clone marker or `.mojian-sync`
  stop loading, and a remembered workspace folder that no longer exists is never recreated. IO/permission errors propagate without renaming data or returning empty
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
