# Native macOS client

## Scope and platform boundary

Native AppKit desktop client, version 0.2.0, Apple Silicon arm64, deployment target macOS 13.
Reuse `MojianCore` JSON/geometry/InkPath/archive/output algorithms. Native menus, NSWindow,
NSToolbar, NSTableView, NSPopover/NSMenu and NSView event routing remain macOS-owned.
Do not use the simulator, Catalyst or old iOS output as a desktop deliverable.
The supplied `shared/branding/Mojian_macOS.icns` is packaged unchanged.

This platform addition leaves Windows/Android adapters and outputs untouched. Two portable core
changes preserve unknown notebook/section fields and expose an optional A4 gap argument; the iOS
default remains unchanged. Desktop uses current shared 32-unit A4 spacing and loads the exact
`shared/page-backgrounds.json` resource rather than defining another spacing/palette catalog.

## Canonical storage and safe baseline access

`CanonicalStore` reads v1/v2 indexes and canonical v2 page documents. `AppearanceBridge` derives
an iOS-style Page display copy from canonical appearance without writing the display theme back.
New local pages include canonical appearance; notebook/section/page/root extension fields survive.
A missing indexed document is an error. A corrupt current file can read a valid `.previous`;
reads do not repair source bytes, and a later write never rotates corrupt bytes over valid recovery.

First launch requires an explicit empty-folder create or existing-folder open. Only libraries
created by this Mac client are writable. External folders are read-only; native-sync/Syncthing
markers prohibit local writing. No sync credentials, daemon, server calls or adopted clones.
Opening read-only must not create a lock, settings, notebooks, recovery files or directories.

Writable local folders use an advisory OS lock. Safe relative IDs and symlink rejection protect
page/asset paths. Observed file stat changes refuse stale writes. This is a single-local-writer
baseline, not a filesystem-wide compare-and-swap guarantee against arbitrary concurrent programs.
Serial worker saves immutable JSON snapshots with explicit generations. Close/navigation flush
pending work and keep the current binding/dirty snapshot when saving fails. A completed old save
must not clear a newer dirty document. Page writes increase revisions monotonically; clean viewing
never saves page/library state. Assets publish before their referencing page, pages before index.
Failed imports may leave unindexed staged page-owned files but never announce a partial notebook.

## Desktop chrome (0.2) and macOS adaptation

The window uses the Windows/Android information architecture and palette from `windows-desktop.md`
and `workspace-ui.md`, not a generic source-list skin: ribbon tabs, a 50 pt icon-only ribbon
(`#232323`), a 32 pt rail with independent section/page pane toggles, a 196 pt black section pane,
a 196 pt gray page pane, then the `#0A0A0A` workspace. Rows are draw-based in virtualized tables:
notebooks show the Fluent notebook icon in the shared accent cycle, sections a 4 pt color bar and
Fluent chevron (rotated, never a font glyph), pages title plus time/date and a muted `PDF` suffix.
Selection is `#374048`; the focused section text is lavender `#DAB2E9`. No pane-header titles.

Icons are generated mechanically from `android/app/src/main/res/drawable` by
`macos/tools/generate-icons.py` into `Resources/fluent-icons.json` (absolute M/L/C/Z, arcs converted,
group transforms applied, literal non-white colors kept). Do not substitute SF Symbols or redraw them.
Rerun the generator when Android vectors change.

macOS adaptations, modelled on how OneNote for Mac adapts the Windows client:

- Ribbon tabs `开始/插入/绘图/视图` sit in the unified title bar beside the traffic lights (an
  NSToolbar item), so no vertical space is spent on a separate tab row. There is no `文件` tab:
  file, export, print and library commands stay in the menu bar; device options are a Settings
  window (⌘,) instead of Backstage.
- Tool flyouts are transient NSPopovers; the add menus, background/paper/zoom dropdowns and pane
  context menus are native NSMenus; prompts and confirmations are window sheets.
- Mouse/trackpad routing follows OneNote for Mac: the Draw tab's pointer (`cursor`) returns the
  mouse to direct selection; choosing any tool preset makes the mouse use that tool. A tablet always
  uses the active preset. The choice is persisted per device. Windows keeps its own mouse contract.
- `整页视图` (⌘⇧F) hides tabs/ribbon/rail and panes, keeping transient floating pane buttons; native
  full screen (⌃⌘F) is separate.
- Deletion commits the index first, then moves page files/assets (or the notebook folder) to the
  macOS Trash so a mistaken delete is recoverable; a failed move only leaves an unreferenced orphan.

Ribbon content is limited to commands that work on macOS: clipboard, font size/bold/italic/
underline/strikethrough/alignment on selected text boxes, text and image insertion, Undo/Redo,
pointer, add-only tool presets (lasso, eraser, highlighters, pens, shape, hand), background format,
clear page, full page, zoom lock/presets/in/out/100%/page width, baseline, paper size, title
visibility and the global dark mode. Presets follow the two-click contract; a click that dismissed a
transient popover must not reopen it. Presets, pane visibility, expansion and mouse routing are
device-local preferences and never rewrite canonical files. Context menus offer rename, new
(sub)section, export/print and delete only; move/copy/merge/link remain absent until implemented.

## Native interaction and rendering

Native mouse selects/marquee by default; explicit mouse writing enables the pen tools. Tablet
point events carry pressure/tilt. Option/middle drag pans, scrolling pans, pinch/Command-scroll
anchor zoom. Selection uses the existing body-first eight handles and ratio-preserving corners.
Temporary right lasso and Escape cancellation do not change the active tool. Native NSTextView
editor supports input methods and multiline text; text responders retain ordinary editing shortcuts.

Renderer binds/caches scene order, ink paths and bounds once on content binding; viewport frames
cull by visible rectangles and never serialize data. Shared nonzero/F0 fill and legacy opaque pen
rules apply. Paper patterns remain below images/PDF/ink. Legacy imported pages include negative
content extent without moving source geometry. Fixed PDFs retain MediaBox bounds. Screenshot
background recoloring/complete source color adaptation remains an explicit gap.

## Output and limitations

Editable archive import stages validation in a disposable folder before publishing a coexisting
notebook with remapped IDs. Export adapts only hierarchy appearance into the existing exchange
manifest and keeps canonical native page content/assets. Native PDF output renders physical sheets;
A4 uses current 32-unit gaps, boundless/legacy output includes negative coordinates. System printing
uses PDFKit. Full export scopes/ranges, native-sync transport, layer UI, split slots, advanced rich
text/table/attachment/PDF authoring and physical stylus validation remain parity work.

## Verification

Storage/session regressions and the portable iOS/core suite must pass. Native bundle acceptance
runs `Mojian.app/Contents/MacOS/Mojian --smoke-test /tmp/.agents/<new-folder>` only against generated
notes. It dispatches real NSEvent mouse messages through NSWindow, checks Undo/Redo, disk readback,
view-only byte invariance, PDF output, and captures the actual native view tree. This is a scripted
native fixture, not manual physical mouse/pen acceptance or live-library sync adoption.
Current results are recorded in the monthly log after execution.
