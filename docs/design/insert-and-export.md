# Insert Objects and Export Containers

Last verified: 2026-08-23

Read this document before changing insert commands, embedded assets, note links, sticker presets,
spreadsheet/PDF previews, or export formats.

## Scope

The Insert ribbon implements tables, file attachments, PDF printouts, spreadsheet previews,
multi-image insertion, stickers, and links to another note. `插入空间` remains owned by the Draw
ribbon. `屏幕剪辑` and `页面模板` are intentionally omitted.

## Object and asset boundary

- Every imported file is copied immediately into the application's private notebook asset store.
  A page object never depends on the picker URI remaining available.
- A file attachment is a compact link object backed by an embedded asset. Tapping it opens the
  embedded copy through a read-only `FileProvider` URI.
- A note link stores only notebook, section, and page IDs plus a display title. It is intentionally
  not an embedded copy and may become unresolved when moved outside its library.
- A PDF printout keeps the original PDF asset and creates one PNG-backed image object per rendered
  page. It therefore behaves like a batch image insertion while preserving the source PDF in the
  private package.
- A spreadsheet keeps the original workbook and a bounded first-sheet cell preview. Tapping it
  delegates editing to an installed spreadsheet application. Spreadsheet previews and inserted
  tables automatically use dark cells/light text on the canvas and white cells/dark text in export;
  this is theme adaptation, not a user-selectable image filter.
- Normal images support multi-selection. Stickers use the same image object and asset pipeline;
  built-in presets are rendered into page-owned PNG assets, while user presets are first copied to
  the sticker library and then copied into the target page.
- Image inversion is a view-only property chosen at import time. Its color transform follows the
  vault's `invert(...) hue-rotate(180deg)` method rather than a plain channel invert. The invert
  percentage is recalculated for this application's `#1b1b1b` paper: `invert(89.4118%)` maps source
  white exactly to RGB `27,27,27`, while the hue rotation restores color identity as far as this
  CSS-style transform permits. Options provide separate policies for normal images and PDF
  printouts; each offers `导入时询问`, `自动反色`, and `保持原图`. Ask mode applies one answer to a
  selected image batch or the entire selected PDF. The unmodified source
  bytes are always retained, package export always includes those bytes, and both PDF/private layer
  rendering bypass the view filter.
- Tables are page objects with an explicit row/column count and cell strings. The size picker adds
  a table and tapping that table opens its cell editor.

## Placement and persistence

Inserted objects use document coordinates and flow downward from the first visible insertion
position. A batch preserves picker order. Insert-space operations shift objects whose top edge is
at or below the insertion baseline.

Page object JSON lives under `files/notebooks/<notebook-id>/pages/<page-id>.json`. Assets live under
`files/notebooks/<notebook-id>/assets/<page-id>/`. Mutations are persisted immediately. This store
is product-owned and is not Notein's proprietary backup format.

## Export

### Layered PDF

PDF export writes the current visible page. It contains three Optional Content Groups (PDF layers):

1. `页面背景`
2. `插入内容`
3. `墨迹与文字`

Export is intentionally light-theme: the page background becomes white and logical black ink is
rendered black; blue, red, and other pen colors remain unchanged. View-only image inversion is not
applied. Each group is a separate raster XObject with alpha preserved through a soft mask. The PDF is
viewable in ordinary readers; readers that expose Optional Content Groups can independently toggle
the three layers. This is layered output, not a claim that individual ink strokes remain editable
PDF vector paths.

### Private notebook package

The `.mjn` format is a ZIP container owned by this project. It contains `manifest.json`, page JSON,
all embedded page assets, and rendered layer snapshots for the currently open page. The manifest
records format/version and notebook hierarchy. Future importers must reject unsupported major
versions rather than guessing.

PDF is for visual sharing and cannot expose embedded attachments for download. `.mjn` is the
portable editable/archive boundary that carries those original files.

## UI contract

The Insert ribbon follows the existing compact dark desktop rhythm. Each command uses one simple
line icon, a short Chinese label, and a quiet hover/pressed surface. Image and sticker are adjacent.
Dialogs use the same dark surfaces, 2--3 dp corners, thin borders, and restrained purple accent.
