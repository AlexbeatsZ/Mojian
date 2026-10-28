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

This removes the previous whole-notebook native view/ink retention, not all large-output risk.
The current editor and one export page coexist; summary bounds scale with source content.
Single-page binding, text measurement, image decoding and native drawing still use the UI
thread. Android `PdfDocument` retains native records for the complete PDF until write/close;
very long dense PDFs can still exhaust memory. Cancellation works between preparation/drawing
steps and cannot preempt a currently executing native draw or final write.

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
- A4 continuous notes export each source sheet separately. Editing/viewport-driven spare sheets
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
native `PdfDocument`. Scope export must never navigate/bind the live page to render other pages.
Android's native adapter respects output page ranges; writes/compression run on the serial I/O
executor after UI-owned vector capture. PNG pages are captured individually and recycled.

## Current validation (2026-10-28)

Android 0.7.0 passes 228/228 unit tests, debug/test APK compilation and lint (0 errors,
136 warnings). Product-session native tests compile but were not installed/executed. The final
Windows build/publish succeeds, but its full suite is not green: existing STA/Application test
lifetime problems can leave subsequent windows unloaded. The export-panel test passes in its
own testhost on both baseline HEAD 22126a5 and current source, and the same-process stale-summary
failure reproduces on HEAD after another window test. See the dated log for exact evidence.
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
