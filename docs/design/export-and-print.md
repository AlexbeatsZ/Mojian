# Export scopes and physical pagination

Last verified: 2026-10-28

## Android preparation and memory boundary (2026-10-28)

The product export/print entries capture page identities, not a map of fully decoded page views.
`ExportProductSession` prepares summaries on the serial worker; `ExportPlan` retains only the
logical source index and physical sheet geometry. Options summaries and print-image discovery
also run off the UI thread, rejecting results after dialog dismissal or a newer request.

Output acquires one logical page at a time, reuses it for its physical sheets, and releases its
layers on success, failure or cancellation before acquiring the next page. Scene indexing and
ordering reuse `SharedSceneComposer`; the existing `SharedContentCodec.readText` provides a
pure read without mutating the editor's restored-text metadata cache. Source size/time checks reject changed pages instead of
silently combining generations. They are not a whole-library cross-device snapshot.

A session rejects overlapping page leases. Failed acquisition releases the lease gate, and
closing an old lease twice cannot release a newer lease. Layer cleanup attempts every layer
even if an earlier cleanup step throws. Output indices are validated before acquiring any page.
The print adapter retains the most recently requested native paper/margins across repeated
equal-attribute layout calls, rejects queued stale writes and reports executor refusal.

Editor rich-text baselines use the existing weak-identity cache keyed by document instance,
not persistent notebook/page IDs: separate clones with the same IDs cannot replace each other's
baseline, and closed pages do not remain rooted in a process-global string-key map. Tag/text
preservation indexes each prior array once per save rather than rescanning it for each item.

The current editor and one export page coexist; summary bounds scale with source content.
Single-page binding, text measurement, image decoding and native drawing use the dedicated
offscreen render worker described below. PDF and native print use one native `PdfDocument` per
physical sheet: finish, write to a private cache file, close, then clone its page/resources into
PDFBox-Android with temp-file-only stream storage. A fresh per-page `PDFCloneUtility` follows the
upstream optimized merger's page/resource copying without retaining prior source object maps or
performing irrelevant form/catalog merges. Source documents close and intermediate pages delete
immediately; success, failure and cancellation close the destination and remove its private
scratch directory. Vectors, text, page sizes and page order are retained, not rasterized.
Native records and content streams therefore no longer grow with total output content in RAM;
page/resource metadata still scales with output size. A single input page must still fit in memory.
Cancellation is checked between scene items, physical sheets, merges and final output chunks;
it cannot interrupt a native operation already executing. Disk exhaustion reports failure rather
than silently switching to whole-document native recording. Ink, objects and rich text reject
off-sheet bounds before path construction, image decode or StaticLayout cloning.

The product has one provider-based print route. Its unused eager-source print branch and the
Activity's legacy whole-canvas PDF fallback were removed rather than retained as alternate paths.
A returned picker result whose export session was lost is rejected; it must not unexpectedly
export the currently selected page instead. PNG renders no longer wait on main either.

PDF stream tests include 48 ordered pages, preserved vector commands/dimensions, damaged input,
cancellation, scratch cleanup and 96 MiB of uncompressed page streams merged/saved in a separate
64 MiB-heap JVM. Native tests additionally check 32 individually recorded pages with both text
and colored geometry using Android's independent `PdfRenderer`; these remain compile-only.
The dependency is the Apache-2.0 PDFBox Android port, pinned to `2.0.27.0`. Its optional
BouncyCastle dependency group is excluded: this route only clones our own unencrypted native
pages and does not expose encrypted-document import, certificates, signing or a PDFBox network
client. JVM merge/save tests run with the same dependency exclusion.

### Android offscreen render worker

`ExportProductSession` owns one lazily started `ExportRenderWorker` (`HandlerThread` with a
Looper). Summary text measurement and all export-only `DryInkView`, `InsertContentLayer`,
`CanvasTextInputLayer`/EditText and `CanvasTagLayer` construction, binding, layout, rendering
and release run on this same thread. These layers are never attached to a window, focused,
or shared with the editor. Existing layer export methods remain the renderer; no second
renderer or whole-document view cache is introduced. Only one logical page lease is allowed.

The worker creates its own configuration Context from the application Context, then its own
Material themed Context on its Looper. Its `getMainLooper()` returns the owner Looper so
context-bound handlers cannot select the Activity's main Looper. No Activity, window inflater,
IME interaction or attached View is passed to the worker. Unattached View.post actions stay
in that View's private run queue; required export measurement/layout is explicit, rather than
depending on attachment to execute those actions. Releasing the editors releases these queues.
The existing text renderer draws a StaticLayout; vector ink draws paths, not the live
ViewStrokeRenderer display-list route. Only software Bitmap/PdfDocument canvases are accepted.

`lease.source.render(canvas, matrix, options)` synchronously delegates to the owner thread.
Callers must exclusively hand over Canvas/Matrix until it returns, keep the lease open, and
invoke it from their export executor. Software Canvas/PdfDocument startPage, finishPage,
writeTo and close can remain sequential on the caller with no concurrent access during render;
there is no worker-created hardware RecordingCanvas/RenderNode crossing this boundary.
Remove the enclosing main `ui.call` from PDF and physical PNG output call sites: delegation
preserves compatibility with old callers but synchronous invocation from main still waits.
Provider sources are not safe to render after lease close. Non-provider legacy sources retain
their own threading contract. `cacheDirectory` lazily initializes PDFBoxResourceLoader once per
session using applicationContext and returns the application cache directory.

Cancel marks the session inactive immediately and asynchronously queues cleanup behind all
accepted work. It never waits on main. No new worker calls are accepted afterward. A draw or
bind already executing finishes/observes cancellation before cleanup; all layer cleanup steps
are attempted before clearing callbacks and quitting the Looper. Closing a cancelled lease is
safe because queued shutdown owns its cleanup. Interrupted synchronous callers keep waiting
for the accepted task and restore their interrupt flag afterward, avoiding abandoned acquired
leases. Call `session.cancel()` on every terminal dialog/export/print lifecycle path, including
summary-only sessions; idle sessions retain one Looper until cancelled. There are no per-sheet
threads. Multiple simultaneously live sessions each own at most one render thread.

Threading references inspected for this implementation:
- [AndroidX AsyncLayoutInflater](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/asynclayoutinflater/asynclayoutinflater/src/main/java/androidx/asynclayoutinflater/view/AsyncLayoutInflater.java):
  ordinary asynchronous inflation cannot assume Handler/myLooper support; our owner has a Looper.
- [AOSP View](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java)
  and [Editor](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/Editor.java):
  View owns a RenderNode, unattached posts have a private queue, and editable text has owner-thread state.
- [AOSP RenderNode threading](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/RenderNode.java):
  creation/use may occur on any one thread; nodes are not thread safe and must stay on their drawing thread.
- [AndroidX ViewStrokeRenderer](https://github.com/androidx/androidx/blob/androidx-main/ink/ink-rendering/src/androidMain/kotlin/androidx/ink/rendering/android/view/ViewStrokeRenderer.android.kt)
  and [CanvasStrokeRenderer](https://github.com/androidx/androidx/blob/androidx-main/ink/ink-rendering/src/androidMain/kotlin/androidx/ink/rendering/android/canvas/CanvasStrokeRenderer.android.kt):
  live View drawing has its own canvas/display-list constraints. Export continues to use
  `DryInkView.renderVectorInkForExport` and shared stroke paths, not `View.draw/onDraw`.

Instrumentation regressions cover real offscreen EditText layout/software draw, worker identity,
main-Looper responsiveness during blocked render work, interrupted waits, queued cancellation
cleanup, thread termination and provider stale-source rejection. Compilation alone does not prove
OEM View/Ink behavior or physical latency; these tests must run on a disposable product/device.

Read before changing the Export/Print surfaces, scope resolution, output page geometry, or native
print adapters. Rendering color is owned by `OutputAppearance`; object/container details remain in
`insert-and-export.md`.

Background-pattern geometry/colors and the shared 32-unit A4 gap are defined in
`page-backgrounds.md` and packaged from `shared/page-backgrounds.json`. Live canvas and output use
the same document-space spacing; output widths are physical while live rules keep the original
desktop screen width. Both pattern palettes currently use the original uniform desktop gray.

## User-visible behavior

- Export has Page, Section and Notebook scopes. Section includes its descendants; Notebook follows
  the canonical section tree and page order, regardless of collapsed navigation rows. Empty or
  unavailable scopes disable the action with an explanation.
- Windows offers vector PDF, editable `.mjn`, numbered PNG images in a ZIP (200 DPI), and XPS.
  Android offers PDF, `.mjn`, and the same image ZIP. No proprietary OneNote/Word formats are
  advertised without an actual writer.
- PDF, image ZIP, XPS and printing share the same physical sheet plan. The page-count summary and
  page range (`1-3,5`, blank = all) use physical output pages after combining the chosen scope.
  `.mjn` preserves editable logical pages, assets and original geometry; it does not cut drawings
  into physical paper. A single-page package uses the existing one-section package schema.
- Paper choices are Automatic, A4, A3 and Letter; orientation is portrait/landscape and margins are
  0/6/12/20 mm. Automatic means A4 for ordinary notes and the original MediaBox for imported PDF.
  Explicit paper uses uniform scaling, never independent X/Y stretch.
- A4 continuous notes export each source sheet separately. Content-driven or historical spare sheets
  are omitted by default using the current content bottom; disabling that option includes them.
  Internal empty sheets keep their position. Normal workspace gaps are omitted, but content in a
  gap belongs to the preceding physical sheet so gap ink/objects are never silently dropped.
- Boundless notes use current mixed-content bounds, including negative-coordinate overflow.
  They fit wide content to usable paper width without upscaling narrow content, then cut downward
  into full-size output pages. Deletion and viewport history do not retain empty historical paper.
  A wholly empty logical note yields one blank sheet.
- Source PDF pages preserve their original page boundary in Automatic mode. Native Android PDF
  dimensions remain integer points. Actual PDF/image assets remain image content in the current
  renderer; authored/imported ink and text remain vector geometry.

## Print and color boundary

The print panel remembers its last reusable choices and restores them on reopen/restart. There
is no separate default-print settings surface. See `settings-memory.md` for the persisted fields,
page-range validation and content-specific image selection boundary.

Printing opens the real Windows print dialog or Android native print preview. Its pages come from
the same planner used by export. Export preview shows export appearance; print preview shows print
appearance. Printing never changes saved page appearance, image inversion, selected objects,
viewport or session-only Android freehand.

Ordinary export preserves the displayed page appearance. Dark-page printing makes neutral black
paper white and white/logical-white writing black, preserving intentional colors and colored paper. The print panel lists
images from dark pages and lets the user toggle inversion relative to display; currently inverted
images are checked by default. The color explanation and image controls are hidden when the scope
contains only light pages. `隐藏背景格式` is a print-only option available in either theme; it omits
rules/grid/dots without changing saved settings. It is off by default.

Windows prints a vector `FixedDocument`; PDF writes the same native `DrawingGroup` scenes with
per-sheet clipping/transforms and valid OCGs. Android snapshots each logical page into independent
offscreen ink/object/text/tag layers, including live page-session strokes, and records them on a
native per-sheet `PdfDocument` and disk-backed output assembly. Scope export must never
navigate/bind the live page to render other pages. Android's native adapter respects output page
ranges; writes/compression run on the serial I/O executor and rendering on the offscreen owner.
PNG pages are captured individually and recycled.

## Current validation (2026-10-28)

The integrated source passes 239 Android unit tests with no failures (one additional opt-in public
HTTPS test is skipped without credentials), debug/test APK compilation and lint (0 errors,
137 warnings). Product-session native tests compile but were not installed/executed. Windows
Debug and Release full suites each pass 191/191, including real-window export, caret and screenshot
tests. One persistent shared STA/Application/Dispatcher fixes the earlier short-lived STA reuse;
the caret test now clears default sample content before exercising a genuinely blank click.
The historical counts below describe the 2026-10-08 delivery, not a current all-suite result.

## Evidence and failure boundary

The earlier morning two-sheet homework was a one-off export; the actual product still generated
one MediaBox from the continuous canvas. A native PDF with vectors does not prove pagination.
Require the real product route, fixed MediaBoxes, physical page counts and an independent render.
Do not treat a print-shaped PDF button or an Android shell toast as real printing.

Tests cover grown-but-empty A4 sheets, gap content, negative overflow, long/wide boundless notes,
source-PDF MediaBoxes, landscape paper, validated ranges, PDF/print/image/XPS page agreement and
actual WPF Export/Print surfaces. Optional `MOJIAN_PAGINATION_PAGE_JSON` replays the user's unchanged
two-sheet homework into scratch outputs. Build Android integration tests separately; never run a
connected Gradle test against the only installed session-bound product.

The final combined Windows suite passes 158/158, including actual Export/Print controls, image
checkbox defaults/changes without page mutation, both theme visibility rules, and the unchanged
homework's two native-vector pages. Android passes 147/147 unit tests plus APK, lint (0 errors,
116 warnings) and separate native-test APK compilation. Both current artifacts were refreshed;
Android device output interaction remains unverified while its live session is preserved.
