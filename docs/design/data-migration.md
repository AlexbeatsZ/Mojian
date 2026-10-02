# Notein and OneNote data migration

Last verified: 2026-10-02

Read this document before changing `tools/data-migration`, imported notebook
layout, Notein backup parsing, OneNote COM export, or migrated PDF behavior.

## Priority and source safety

The default migration contains editable native content and original embedded
objects. Visual image copies require `--include-visual-fallbacks`; they do not
populate the normal section tree. Notein's supported local
backup and OneNote's COM API are read-only sources. A migration is built and
validated in a staging workspace before it can replace live Mojian data.

## Imported structure

- OneNote ISF retains each stroke's filled vector outline from WPF
  `Stroke.GetGeometry`, its original color, drawing attributes, and input
  points. `StrokeCollection.GetBounds` supplies the source drawing anchor;
  the centerline minimum is not the outline minimum. Original images and
  files remain independent objects; text remains editable. Do not reinterpret
  imported ISF through Mojian's fountain pen. The C# decoder avoids millions
  of PowerShell point conversions. Version-1 exports must be regenerated.
- Run `Enrich-OneNoteExport.ps1` after COM export. `InsertedFile` attachments
  may have neither `Data` nor `Position`; recover their original bytes from
  COM's `pathCache`, with the owning outline's position when present. Printout
  images keep their `isPrintOut` flag and source z-order. Match them to original
  PDF pages using normalized fingerprints with a strict threshold and ambiguity
  rejection. A failed/ambiguous match remains an independent image. Never use a
  whole-page OneNote PDF export as an editable printout's original source,
  because that export already includes annotations.
  Metadata stays in small `enrichment/<page-id>.json` sidecars: PowerShell
  must not deserialize/rewrite the entire native point/path export again.
  Match metadata to image position/size instead of assuming identical ordinals.
  Existing cache copies may be read-only; resume without overwriting them.
- Notein legacy and current stroke records become native strokes. Plain-note
  pages are editable. For PDF notes, only pages carrying user content become
  editable annotation pages; the complete source PDF is embedded once as a
  backup, after those editable pages.
- External Notein preview PNGs live in `原貌图片`; they never share the editable
  canvas, because deleting an editable stroke must also remove its visual
  appearance.
- Stable imported notebook/page IDs make a rerun replace only prior imports and
  preserve unrelated Mojian notebooks.
- The user requested complete removal of Notein pages lacking their original
  PDF, including annotations, on 2026-10-02. Current-workspace rebuilds must use
  `--skip-missing-notein-pdfs`; do not reintroduce those notes or placeholders.
  Original source backups and the older workspace remain recoverable.

## Native imported scene (Windows migration)

Imported strokes own a filled path (`vectorPath`), original `colorHex`, an
affine `vectorTransform`, conservative cached `vectorBounds`, input points and
source brush metadata. Movement/resize changes both the path transform and
input points; whole-stroke erasing, lasso, copy and undo operate on that stroke.
The path is authoritative for rendering and hit testing. A PNG cannot stand
in for a stroke. Only imported logical black adapts to dark paper; white cover
ink and arbitrary source colors remain intact.

Notein's sRGB Android ColorLong stores ARGB in its upper 32 bits. Layers use
`NoteContentEntity.page_layer_list` order and `PageLayerEntity.visible`; each
ink, shape, quote, image and text item keeps `layer_id` and source creation
order. Mixed rendering/hit testing interleave images, strokes and text within
those layers. Hidden layers persist and cannot be selected/erased. A canvas
context-menu layer list changes visibility. Image ordering commands reorder
relative to ink/text in the same source layer. PDF backgrounds remain fixed.

Notein records provide inputs/brush parameters, not the complete proprietary
brush graph or exact filled outline. Its reconstructed vector contours remain
editable and resolution independent, but exact pen-shape equivalence remains
unverified. OneNote native ISF outlines have no such brush-reconstruction step.

This migration/import route is Windows-only: Android currently has no
Notein/OneNote backup importer, and its runtime ink is session based. Do not
claim that Windows imported vectors are rendered or editable on Android.
The existing `.mjn` Windows document copy retains this scene; Android parity
requires a durable native scene reader/renderer before that claim is valid.

## PDF display contract

An original PDF remains the authoritative vector asset. The WPF canvas is not
a PDF object model: it rasterizes the requested page for display. A low-quality
implementation renders once and scales that bitmap forever; this is not
acceptable. PDF-backed objects retain `SourceAssetName` and `SourcePageNumber`,
show the stored preview immediately, and asynchronously rerender from the source
PDF in zoom/DPI-sized 512-pixel buckets between 2048 and 4096 pixels wide.

The source PDF, not the preview PNG, remains available for re-rendering and
package/export provenance. A visual fallback page is explicitly non-editable;
PDF-backed annotation strokes above it remain ordinary editable Mojian strokes.

## Large-page performance contract

Imported source pages can be much larger than pages authored natively in Mojian. The verified
staging corpus includes pages above 10,000 strokes and 400,000 input points. Migration therefore
writes page JSON without indentation, while library/report metadata remains readable. The Windows
and Android canvases cache each stroke's document-space bounds and submit only viewport-intersecting
strokes during interactive rendering; export intentionally bypasses that culling. Panning and zooming
must update viewport state without synchronously serializing the whole page. Navigation and shutdown
still flush the latest viewport, while content mutations retain ordinary atomic autosave.

## Known source limits

- The 2026-08-22 Notein backup contains only 43 of 112 original source PDFs.
  Missing PDFs cannot be reconstructed as vector documents. Available external
  PNG previews are retained separately.
- Expanding every unannotated page would add 12,663 navigation rows. Complete
  source PDFs are retained instead; only annotated pages become native Mojian
  pages.
- Two very large OneNote pages exceeded the exact-PDF helper timeout. Their
  editable COM/ISF content remains imported, without a visual fallback.
- The three-color authoring shelf does not constrain imports. Imported ink
  stores the original ARGB and renders that color directly.
- Native outlines do not turn source raster screenshots/scans into editable
  handwriting. Original embedded image objects can move/resize independently.
