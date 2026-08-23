# Insert Objects and Export Containers

Last verified: 2026-08-24

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
- PDF import from the gray page-pane `+` is a different operation from an Insert-ribbon printout.
  It creates one `NotePage` per source PDF page. Each new page stores the original PDF reference,
  page number/count, original PDF point dimensions, and a private rendered page image. That image
  is an immutable page-background object at document origin; normal insert objects, ink, text, and
  tags remain above it.
- A spreadsheet keeps the original workbook and a bounded first-sheet cell preview. Tapping it
  delegates editing to an installed spreadsheet application. Spreadsheet previews and inserted
  tables automatically use dark cells/light text on the canvas and white cells/dark text in export;
  this is theme adaptation, not a user-selectable image filter.
- Normal images support batch import. Stickers use the same image object and asset pipeline;
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
- On Android, a finger double tap selects any non-background inserted object, including an image,
  PDF printout, sticker, table, attachment, spreadsheet, or note link. A selected object can be
  dragged by its body or resized from eight compact square handles on a light dotted frame. Each
  handle keeps an approximately 48 dp invisible touch target; corner handles change two
  axes and edge handles change one axis. Holding reaches the context menu before release.
  Immutable PDF page-background objects never participate in this direct selection path.
- Overlapping handle hit regions resolve to the nearest handle, not the first handle in drawing
  order. Body move and resize commit only after platform touch slop. Canvas pan/pinch keeps the
  object selected; a confirmed single tap elsewhere is the sole deselection gesture.
- The lasso's rectangle and freeform variants select every non-background inserted object whose
  document bounds overlap the completed polygon. Images can therefore join ink, tags, and text
  boxes in one shared move/scale/delete selection; immutable PDF page backgrounds remain excluded.
- Image context commands are app-owned and include cut/copy/paste, save-as, a page-link action,
  four stacking operations (`上移一层`, `置于顶层`, `下移一层`, `置于底层`), clockwise/counterclockwise
  90-degree rotation, horizontal/vertical flip, restore original pixel size, and set-as-background.
  Order and rotation are right-side submenus; unavailable link/move/crop commands remain visibly
  disabled instead of silently disappearing. Rotation quarter-turns, both flip flags, size, position,
  and background state are persisted in page JSON with backward-compatible defaults.
- Windows mirrors this object contract with direct double-click selection, the same dotted eight-handle
  frame, mixed freeform/rectangle lasso selection, pointer-positioned dark context menus, asset-aware
  clipboard copies, persisted image transforms, and immutable PDF page backgrounds. The platform's
  light menu template and icon gutter are not used.
- Windows `.mjn` output serializes PDF page view settings as Android `PaperSize.PDF`, includes the
  source asset/document, page number/count, source point dimensions, and custom document dimensions
  in the manifest, and writes image objects with Android's `PDF_PAGE`, background, rotation, and flip
  fields. Do not substitute the desktop-only `PdfPrintout` enum name into the shared container.

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

The live canvas follows the same semantic order. Paper color and rule/grid/dot patterns render below
all inserted images, PDF printouts, stickers, tables, and attachments; dry/wet ink, text, and tags
render above inserted content. Background patterns must not share a top-level view layer with ink,
because doing so makes the pattern bleed across opaque inserted images.

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

For a PDF-backed notebook page, export uses the imported page's original PDF point dimensions for
the output MediaBox rather than the current screen or split-pane pixel size. The rendered source
page remains in `插入内容`, while annotations remain in `墨迹与文字`; view-only inversion is still
bypassed. This preserves writing-over-PDF behavior without flattening the source and annotations
into one inseparable raster layer.

## UI contract

The Insert ribbon follows the existing compact dark desktop rhythm. Each command is a Fluent-style
line icon with no visible name or explanatory copy; the screenshot-reference semantics are table,
attachment, PDF printout, spreadsheet, image, sticker, and link. Image and sticker are adjacent.
Accessibility descriptions remain invisible, and mouse/stylus hover uses an app-owned delayed
tooltip with a `#3A3A3A` background and white text instead of the platform light tooltip. Dialogs
use the same dark surfaces, 2--3 dp corners, thin borders, and restrained purple accent.
All object context menus and command drop-downs use the shared app-owned command surface with Fluent
icons; Android/OEM popup styling is not accepted for these interaction surfaces.
