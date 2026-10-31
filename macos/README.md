# 墨笺 macOS

Native AppKit baseline for Apple Silicon, macOS 13+. This is an independent desktop
application using the portable `ios/Sources/MojianCore` library, not an iOS simulator or Catalyst app.

## Build

```sh
bash macos/tools/build.sh test
bash macos/tools/build.sh build
open macos/artifacts/current/Mojian.app
```

Open `macos/Package.swift` in Xcode for native debugging. The scripts use existing Xcode/Swift
and project-local ZIPFoundation 0.9.19; they do not install or switch system tools.
Build intermediates go to `/tmp/.agents/mojian-macos-client/build`. The sole current `.app`
and distributable ZIP are in `macos/artifacts/current`. The original supplied ICNS is copied unchanged.
The bundle is ad-hoc signed; it has no Developer ID notarization or Intel build.

## First launch and data

The welcome window asks to create a local library in an empty folder or open an existing library.
No sample library is created at an inferred Documents path. Mac-created libraries retain a local
marker and an advisory writer lock. Existing external Windows/Android/native-sync libraries open
read-only. HTTPS synchronization is not implemented in this baseline.

The local folder is canonical v2: `library.json`, `notebooks/<id>/pages/<id>.json`, and page-owned
assets. Read/edit/write retains unknown JSON fields. Atomic writes retain one valid `.previous`.
Missing/corrupt indexed pages stop navigation rather than becoming empty notes. External changes
refuse stale writes. Save errors retain the current dirty document and block navigation/close.
Local viewports, page theme, default new-page background and drawing choices live in macOS
preferences, outside the shared folder. Browsing does not rewrite canonical files.

## Controls

- Native File/Edit/Insert/Draw/View/Window menus, Command shortcuts, traffic lights and resizable panes.
- Notebook and nested-section tree; direct or recursive page list; page-title search; new notebooks,
  sections/subsections/pages and page rename.
- Mouse selects, drags/resizes selected content, or draws a containment marquee. Double-click text
  opens the native editor. Option-drag or middle-button drag pans; trackpad scroll pans; pinch or
  Command-scroll zooms. View presets span 50–6400%.
- Tablet events carry pressure/tilt. `绘图 → 鼠标书写` explicitly enables mouse ink and the chosen
  pen/highlighter/eraser/lasso/hand/shape tool. Right-drag temporarily lassos; Escape cancels gestures.
- Delete removes the mixed selection. Command-C/V/X preserves editable selected content and copies
  asset bytes. Native text fields keep macOS text copy/paste. The clipboard generation owns internal copies.
- Insert text/images, seven geometric shapes, whole-stroke erasing and page-local Undo/Redo.
- `.mjn` notebook import creates a coexisting notebook; export keeps native page data and original assets.
  PDF export uses native vector ink/text/source PDFs; printing opens the macOS print panel.

## Remaining parity

Native HTTPS sync/adoption and writable external workspaces; full notebook/section/page lifecycle
(delete/move/copy/merge/order); independent split slots; advanced rich-text/tables/attachments/link UI;
PDF import, PNG/SVG/selection output; complete print scope/range/image controls and restored choices;
source-layer controls; imported screenshot contrast heuristics; pen hold recognition/pressure eraser
and physical tablet latency. Existing imported images render from their source rather than claiming
Windows/Android screenshot recoloring parity. See `../docs/design/macos-client.md`.
