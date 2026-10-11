# iOS / iPadOS client

## Goal and native boundary

墨笺 gains a native UIKit client for iPhone and iPad, minimum iOS 17.0. This work adds
the Apple platform; it does not change the running Android/Windows clients or their release outputs.
Use the existing dark chrome, hierarchy, page settings, document units and editable `.mjn` boundary.
Read `input-gestures.md`, `insert-and-export.md` and `export-and-print.md` before changing these seams.

## Layout

Safe-area-aware compact ribbon, 44-point input targets, horizontally scrolling commands.
Regular-width iPad shows independent notebook/section and page panes beside the canvas.
Compact width (iPhone, narrow iPad multitasking) shows navigation in a sheet; closing it restores
the entire writing area. Full-page mode retains recovery controls and a floating tool strip.
Width, not device model or orientation, determines layout. A phone has an explicit finger-writing
option because it cannot use Apple Pencil; otherwise touch retains the shared direct-selection contract.

## Ink and Pencil controls

UIKit coalesced Pencil samples carry force, altitude, azimuth and timestamps into the shared
Windows 96-DPI stroke representation. Prediction is transient and never saved. Finished strokes
cache vector paths; wet ink and selection chrome are separate layers. Fountain width, pressure,
flow, highlighter opacity and whole-stroke eraser are adjustable. No proprietary Notein renderer.

Apple Pencil 2 / Pro double-tap and Pencil Pro squeeze each have a persisted action picker.
The default follows the system preference; custom choices include eraser, previous tool, pen,
highlighter, lasso, hand, Undo, Redo, palette, full page and no action. Squeeze acts once at `.ended`;
cancel/begin/change phases do not trigger an action. Apple may consume gestures assigned to system
Shortcuts. USB-C / first-generation Pencil have no app-visible side gesture. Do not invent single,
double and hold button events for these devices. Third-party pen buttons require actual UIKit
key events; only explicitly configured key chords are intercepted. No unverified Bluetooth protocol.

## Persistence and exchange

Private `Documents/Workspace/library.json` plus per-notebook page JSON and asset directories.
Serial atomic writes keep `.previous`; corrupted content produces an error, never a blank replacement.
Autosave flushes on navigation/background. Imports always create a coexisting notebook with new IDs,
preserving page links and source-owner references within the imported tree. `.mjn` version 1 is a ZIP:
manifest, shared `pages`, lossless `windows/pages`, and original assets. Unknown JSON fields survive
read/edit/write. Files document picker and share sheets are the iOS sync boundary; iOS does not
claim an unrestricted always-running Syncthing service. Never overwrite an external working library.

## Verification

SwiftPM tests exercise the real portable model/store/archive/input policies on macOS.
Xcode must also compile the UIKit target and run phone/tablet simulator acceptance; SwiftPM alone
is not iOS runtime proof. Pressure, latency, palm rejection, hover, double-tap and squeeze require
physical iPad/Pencil acceptance. Signing/distribution needs the user's Apple development team.

## Current implementation and remaining parity

Version 0.1.0 includes the native hierarchy, Files exchange, pressure fountain pen/highlighter,
whole-stroke/pressure eraser, lasso and direct containment selection, movement/eight-handle resize,
seven shapes, hold-to-line, 50–6400% zoom, same-note split viewports, full page, editable text/tables,
images/stickers/tags/attachments/note links, PDF annotation pages, native clipboard and SVG/PNG copy.
PDF output keeps native ink/text/source PDF geometry; selection SVG respects source interleaving
and the imported outline fill rule. Imported PDF inversion uses a raster background as on other clients.
A4 output drops historical trailing blank sheets; boundless output includes negative overflow and
slices tall content. PNG uses 288 DPI. Print output converts dark paper/white ink without mutating notes.

The Apple client uses native command sheets and SF Symbols. Independent navigation of split slots,
the full desktop scope/range/export and per-image print conversion panels, first-sheet XLSX preview,
source-layer management, the full rich-text/style/shortcut/preset catalogs and section/page
move/copy/merge/manual-order menus remain parity work. Attachments are embedded and previewed through
Quick Look; a first-sheet spreadsheet preview is not yet claimed. Continuous Syncthing is not embedded.
Do not describe this first native client as complete Android/Windows feature parity.

`RULE_*` names follow Android; the renderer also recognizes historical Windows `RULED_*` aliases.
Coalesced sample timestamps remain precise during input, but persisted `elapsedMilliseconds` is an
integer because the desktop reader requires Int64. Imported unknown page fields/source metadata
are retained. Pencil input bypasses an active text editor and never inherits direct-touch selection.
The source-path importer supports the migration's M/L/H/V/C/Q/Z/F grammar; unsupported grammar
retains the source JSON and uses its available point geometry for rendering.

## Verified 2026-10-08

- Xcode 27.0 (27A266a), iOS 27.0 runtime (24A434); simulator Release and unsigned device arm64
  builds succeeded without application compiler warnings. SwiftPM: 19 tests, zero failures.
- Tests cover lossless source JSON/assets/archive exchange, coexisting import IDs, corrupt-file
  recovery, path guard, integer Windows timestamps, source affine paths/fill-rule holes, zoom anchor,
  pressure travel latch/active eraser, tiny selection resize, containment, shapes, growing paper,
  background exchange aliases, physical output sheets, viewport-only persistence and structurally
  invalid JSON recovery without blank-page replacement.
- iPhone 17e simulator: actual native finger-writing toggle, canvas stroke, Undo (stroke disappears),
  Redo (stroke returns), compact page navigation, A4 title entry and new page appeared. The authored
  test page persisted one stroke/three samples. These are simulator touches, not physical pen latency.
- iPad Pro 11-inch (M5) simulator: portrait and landscape layouts inspected; native Pencil action
  pickers set double-tap to Lasso and squeeze to Undo, then Done. Persisted JSON confirms both values.
- Release updates were installed over both isolated simulator apps without uninstalling. After
  restart, phone ink/new A4 page and tablet double-tap/squeeze settings remained visible. Actual
  mapped keyboard input `e` switched the visible Pen to Eraser and back; simulator keyboard capture
  was returned to off. This validates the keyboard event route, not a physical third-party pen.
- Native PDF export opened the system sharing sheet. An independently parsed/rasterized occupied
  A4 file has MediaBox 595.2756 x 841.8898 pt, zero image objects and native filled curve paths;
  its title, both authored strokes and dark grid render correctly. Export output filenames use note
  titles; switching File returns to the previous ribbon. Full-page commands are centered and reserve
  enough top space to avoid overlapping the title.

- No physical device is connected and no Apple development signing Team is configured. No installed
  device IPA/TestFlight/App Store release or physical Pencil event/pressure/palm/hover acceptance.

Simulator acceptance uses disposable simulator notes only; live Android/Windows notes and release
artifacts are untouched. Build logs/intermediates are in `/tmp/.agents/mojian-ios-*`. Open Device Hub
and select the two booted devices for further simulator checks. Physical signing/installation and
Pencil acceptance remain the next hardware-dependent steps.
