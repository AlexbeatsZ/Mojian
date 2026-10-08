# Insert Objects and Export Containers

Last verified: 2026-10-08

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
- `开始 -> 粘贴` and `Ctrl+V` reuse the same normal-image pipeline when the system clipboard contains
  an image. Windows accepts clipboard bitmaps and copied image-file drop lists; Android accepts
  foreground-readable `ClipData` image URIs. The pasted image is copied immediately into page-owned
  assets, keeps its source aspect ratio, uses the configured normal-image display policy, persists,
  and remains independent of the clipboard after insertion.
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
- On Android and Windows, one ordinary finger/primary-mouse click selects any non-background
  inserted object, including an image, PDF printout, sticker, table, attachment, spreadsheet, or
  note link. Only an already selected object can be dragged by its body or resized from eight compact
  square handles on the shared light dotted frame. Handles use a maximum 24 dp touch radius
  (12 px/dp for mouse). Inside the content bounds, the body inset is capped at a quarter of each
  dimension, leaving at least the central half of both dimensions available for body movement
  even on a tiny or narrow image. This body target takes priority over invisible handle targets.
  Corner handles preserve the starting aspect ratio around the opposite corner; edge handles
  change only their corresponding axis. Holding reaches the context menu before release.
  Immutable PDF page-background objects never participate in this direct selection path.
- Overlapping handle hit regions resolve to the nearest handle, not the first handle in drawing
  order. Body move and resize commit only after platform touch slop. Canvas pan/pinch keeps the
  object selected; a confirmed single tap elsewhere is the sole deselection gesture.
- Resize uses pointer displacement from the original down position, applied to the original
  content handle. It does not snap content to an off-center hit or Android's padded frame.
  Corner displacement projects onto the starting diagonal; crossing the fixed anchor clamps
  without flipping. The frame minimum is 24 screen units, capped by the starting shorter side
  so an already smaller image does not jump larger. Clamp once at the shared frame, never
  independently at each image's width/height, which would distort small items in a mixed selection.
- The stylus lasso's rectangle/freeform variants and the direct-input rectangular marquee select
  every non-background inserted object whose complete document bounds are contained by the completed
  region; partial overlap is not enough. Images can therefore join ink, tags, and text
  boxes in one shared move/scale/delete selection; immutable PDF page backgrounds remain excluded.
- Image context commands are app-owned and include cut/copy/paste, save-as, a page-link action,
  four stacking operations (`上移一层`, `置于顶层`, `下移一层`, `置于底层`), clockwise/counterclockwise
  90-degree rotation, horizontal/vertical flip, restore original pixel size, and set-as-background.
  Order and rotation are right-side submenus; unavailable link/move/crop commands remain visibly
  disabled instead of silently disappearing. Rotation quarter-turns, both flip flags, size, position,
  and background state are persisted in page JSON with backward-compatible defaults.
- Windows mirrors this object contract with ordinary-click selection, the same dotted eight-handle
  frame, mixed pen-lasso/direct-marquee containment selection, pointer-positioned dark context menus, asset-aware
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

Boundless pages follow `docs/design/boundless-canvas.md`: export current mixed-content bounds with
6 mm padding, independently of saved paper size and viewport. Content deletion may reduce the PDF
crop without shrinking the canvas. Imported PDF MediaBox and A4 export behavior remain unchanged.

PDF export writes the current document page in a light theme: paper becomes white, logical black
ink/text becomes black, and blue/red/other intentional pen colors remain unchanged. View-only image
inversion is bypassed. The 2026-10-08 user correction explicitly requires **vector output**.

Windows now records `DrawingGroup` export scenes and writes filled native ink contours, shape
outlines, paper patterns, and glyph outlines as PDF path operators. Cubic/quadratic curves are
preserved; elliptical arcs are converted to cubic segments, rather than flattened into a page
bitmap. Group transforms, clipping and uniform opacity are retained. Actual inserted images remain
image XObjects with their own source pixels and soft masks. The handwriting and text layer must
never become a screenshot, regardless of viewport/zoom or page dimensions. Unsupported drawing or
brush types fail explicitly instead of silently rasterizing the page. Glyph outlines retain the
font's appearance but are not searchable/OCR text. The old bitmap writer remains only for legacy
fixtures, while `.mjn` PNG previews remain intentional snapshots.

Ordinary Windows pages retain the `页面背景`, `插入内容`, and `墨迹与文字` Optional Content Groups.
Imported scenes with source-layer interleaving use one vector composite group in canonical source
order; separating all images below all ink would incorrectly expose covered source strokes.
The Windows writer uses valid `/OC /OC0 BDC` syntax, UTF-16BE layer names and invariant PDF numbers.
The earlier bitmap writer's invalid one-operand BDC was a separate file-syntax defect, not the
cause of the user's reported low-resolution appearance. A correct canvas bitmap is insufficient
proof: inspect and render the actual final PDF with an independent interpreter.

Android now uses native `PdfDocument` and draws AndroidX Ink 1.0.0 `PartitionedMesh` boundary
outlines with `Canvas.drawPath`, rather than exporting GPU ink or a bitmap screenshot. Paper,
tags, native text layouts and inserted-object geometry are recorded on the PDF canvas. Logical
near-white text colors, including foreground spans, are adapted on a copied export layout, without
mutating live editors. Bitmap assets remain native images. This native Android format has no
Optional Content Group toggle; preserving vector ink takes priority over the former raster-layer
format. Native page dimensions are integer PDF points, so fractional source PDF dimensions are
rounded to the nearest point on this client. Both clients keep document coordinates independent
of viewport position and scale. Physical export and printing now follow `export-and-print.md`:
A4 sheets and boundless content are paginated into real output pages through the product entry points.

PDF printout backgrounds are still drawn from the existing original-source raster renderer; the
new guarantee covers authored/imported ink paths, text/glyphs and native geometry, not a claim that
embedded source-PDF pages have been copied as original PDF operators. Original PDF/image assets
remain preserved in `.mjn` and the workspace.

The user's `Chemistry / Chemical Kinetics / 作业 1`, edited at 09:09 on 2026-10-08 (SGT), was
re-exported after rejecting the first raster result. The final two-A4-page vector PDF retains all
1,110 strokes / 26,821 points and omits the trailing blank third sheet in this one-off delivery.
Independent PDF parsing finds zero image XObjects on both pages; ordinary and 600-DPI detail
renders were visually checked with no interpreter errors. The native source workspace is unchanged.
Windows 132/132 tests include independent PDF rendering of vector ink/glyphs, transparency, image
orientation and light-theme color adaptation. Android's existing 134 unit tests and build/lint pass (0 errors, 110 warnings); the new native vector integration test is built separately, not run against the sole installed
session-bound tablet product. Tablet runtime acceptance remains pending.

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
