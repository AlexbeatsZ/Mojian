# 墨笺 iOS / iPadOS

Native UIKit application for iPhone and iPad, iOS 17.0+. Open `Mojian.xcodeproj` in
full Xcode. The portable Swift package is a library/test target, not the application entry point.
Read [the design record](../docs/design/ios-client.md) before changing input, persistence or exchange.

## Build and run

```sh
bash ios/tools/build.sh test
bash ios/tools/build.sh simulator
bash ios/tools/build.sh device     # verifies unsigned arm64 device build
```

The simulator application is copied to `ios/artifacts/current/Mojian-simulator.app` (ignored).
Select an installed iPhone or iPad simulator in Xcode and Run. Simulator runtimes can be installed
from Xcode Settings → Components. Build intermediates live in `/tmp/.agents` by default.
`uv run --no-project python ios/tools/generate_project.py` regenerates the deterministic project
when app source files are added. ZIPFoundation 0.9.19 is a project-local SwiftPM dependency.

Physical device deployment: set your development Team in Xcode Signing & Capabilities, connect
the device, enable Developer Mode when required, then Run. For a signed archive:

```sh
MOJIAN_APPLE_TEAM=YOUR_TEAM_ID bash ios/tools/build.sh archive
```

An unsigned device build or simulator `.app` is not an installable IPA. App Store / TestFlight
publication and automatic provisioning are not performed by these scripts.

## Writing and navigation

- Phone/narrow iPad: rail buttons open navigation sheets. Wide iPad: independent 196-point section
  and page panes; rotation/multitasking use available width. The ribbon scrolls horizontally.
- Apple Pencil writes, touch selects, two fingers pan/pinch. `选项 → 手指书写` explicitly enables
  finger ink on a phone or tablet. Coalesced force/tilt samples persist; prediction stays transient.
- Pen, highlighter, whole-stroke eraser, free lasso, hand, seven shapes, hold-to-line, Undo/Redo,
  selection move/eight-handle resize, native clipboard, SVG/PNG copy, editable text and tables,
  tags, images, attachments, stickers, note links and PDF annotation pages.
- `视图` provides 50–6400% zoom, fit width, pinch lock, paper/color/rules (including two-column),
  full page and two views of the same note with independent viewports.
- A blank touch creates an uncommitted text box without immediately opening the keyboard; tap
  it to type. Existing text uses select then edit. Empty boxes disappear on input/navigation loss.
- `选项 → Apple Pencil 侧面操作` independently maps double-tap and squeeze. Defaults follow
  iPadOS; custom choices include eraser, previous tool, pen/highlighter/lasso/hand, Undo/Redo,
  tools, full page or no action. Double-tap requires Pencil 2/Pro; squeeze requires Pro.
  Pencil 1/USB-C have no app-visible side control. Third-party buttons are supported only when
  they produce the keyboard characters explicitly entered in the options; private pen protocols
  are not guessed. The system may consume gestures assigned to Shortcuts.

## Data and output

All notes/assets live in the application's `Documents/Workspace`; exposed through Files.
Atomic serial JSON writes preserve one `.previous` revision. Corrupt files are reported and
preserved. Navigation/background flush; per-page viewport changes use a separate local file.
Imports create a coexisting notebook with new IDs and retain unknown source fields/vector paths.
`文件` imports/exports `.mjn`, exports paginated vector PDF/PNG and opens native AirPrint.
PDF/PNG preserve current display; dark printing converts white writing/paper without altering
saved appearance. Printing can hide paper patterns. PDFs retain native ink/text and source PDF
geometry; inversion requires a raster image for that imported background.

The existing Windows `windows/pages` payload is the lossless stroke/text/layer path. Existing
Android packages only persist the stroke types that Android currently serializes (shapes); its
session-only freehand cannot be recovered from a package that does not contain it. Shared Android
object payloads retain the coordinate convention used by the existing desktop exporter.
Files import/export is the iOS sync boundary; this client does not run a continuous Syncthing daemon.

## Acceptance boundary

Portable tests are necessary but not iOS UI or physical pen acceptance. Phone and tablet simulator
checks, actual build status and remaining features are recorded in `docs/design/ios-client.md`.
The Apple client currently uses native system command sheets and SF Symbols; it does not claim
pixel-identical Android Fluent menus. Same-note split is implemented; independently navigable split
slots, the complete desktop export-scope/range panel, first-sheet XLSX preview and source-layer
management controls are tracked separately. Unknown imported metadata survives exchange.
Physical Pencil pressure, palm rejection, hover, double-tap/squeeze and apparent latency still need
the user's hardware. Do not describe simulator input as physical pen acceptance.
