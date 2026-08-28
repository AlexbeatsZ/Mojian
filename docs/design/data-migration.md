# Notein and OneNote data migration

Last verified: 2026-08-28

Read this document before changing `tools/data-migration`, imported notebook
layout, Notein backup parsing, OneNote COM export, or migrated PDF behavior.

## Priority and source safety

The migration order is editable native content, then a separate visual image
copy, then PDF only when it is the only exact source. Notein's supported local
backup and OneNote's COM API are read-only sources. A migration is built and
validated in a staging workspace before it can replace live Mojian data.

## Imported structure

- OneNote ISF is decoded into Mojian strokes. Text and embedded images/files
  become native page items and objects. Each source section keeps editable
  pages at its own level; `原貌图片（不可编辑回退）` is a collapsed child.
- Notein legacy and current stroke records become native strokes. Plain-note
  pages are editable. For PDF notes, only pages carrying user content become
  editable annotation pages; the complete source PDF is embedded once as a
  backup, after those editable pages.
- External Notein preview PNGs live in `原貌图片`; they never share the editable
  canvas, because deleting an editable stroke must also remove its visual
  appearance.
- Stable imported notebook/page IDs make a rerun replace only prior imports and
  preserve unrelated Mojian notebooks.

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
- Mojian currently has three logical ink colors. Other source colors are mapped
  to the nearest logical palette; exact visual colors remain in fallback images.
