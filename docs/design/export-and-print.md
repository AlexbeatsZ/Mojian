# Export scopes and physical pagination

Last verified: 2026-10-08

Read before changing the Export/Print surfaces, scope resolution, output page geometry, or native
print adapters. Rendering color is owned by `OutputAppearance`; object/container details remain in
`insert-and-export.md`.

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

Ordinary export preserves the displayed page appearance. Dark-page printing makes the paper white
and near-white/logical-white writing black, preserving intentional colors. The print panel lists
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
