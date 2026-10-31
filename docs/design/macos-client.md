# Native macOS client

## Scope and platform boundary

Native AppKit desktop baseline, version 0.1.0, Apple Silicon arm64, deployment target macOS 13.
Reuse `MojianCore` JSON/geometry/InkPath/archive/output algorithms. Native menus, NSWindow,
NSSplitViewController, NSOutlineView, NSTableView and NSView event routing remain macOS-owned.
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
