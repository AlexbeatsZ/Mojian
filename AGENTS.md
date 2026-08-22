# Goal

- Assess whether the Android Notein app on the user's tablet can be brought to Windows with PC-side writing and file-based synchronization through Syncthing instead of Notein cloud sync.
- Prioritize a clean-room handwriting engine that reproduces Notein's pen character and low apparent latency, because both the compatibility route and an independent product need it.
- Preserve the user's existing Notein data. Reverse engineering is for interoperability with the user's own notes; do not publish proprietary app code or keys.

# Current State

- Repository was initialized with feasibility commit `817787a`; it has no remote.
- The tablet keeps Android wireless debugging enabled (Xiaomi `23046RP50C`, Android 15 / API 35, arm64), but its ADB connection port changes. A user-supplied endpoint such as the last verified `192.168.31.243:45337` is current-session information only: connect to it immediately when supplied, never treat it as a fixed port, and never reuse an older recorded port without verification. When no current port is available, check existing `adb devices` connections and retry `adb mdns services`; an empty mDNS result does not mean the tablet is offline or wireless debugging is disabled.
- Installed China package: `com.orion.notein`, `v2.0.10.0` (`versionCode=343`, `minSdk=26`, `targetSdk=35`), single 114,190,617-byte APK, installed from Xiaomi Market.
- APK is non-debuggable and app-private `/data/user/0/com.orion.notein` cannot be read with `run-as`.
- App-specific external files are readable at `/sdcard/Android/data/com.orion.notein/files`; current snapshot is about 256 MiB / 5,638 files, mainly UUID-named blobs and PNGs.
- Static scan shows a native Android/Kotlin/Java app, not Flutter/React Native. It includes arm64-only native libraries such as `libink.so`, PDFium, OpenCV, SQLiteX, and Baidu Protect. Xiaomi CloudKit database/file sync providers are bundled.
- Runtime resources and logs show that editable data depends on a private `NoteContentDatabaseProvider`; the readable external directory is predominantly PNG previews.
- A user-authorized full local backup was created through Android's save picker as `/sdcard/Download/backup.zip` (1,579,602,577 bytes). Keep the tablet copy; the analysis copy is temporary and must not be committed.
- The backup is an unencrypted ZIP containing 167 per-note `.in` nested ZIPs, two `.template` entries, and one `.tree`; per-note containers include a Room/SQLite v25 database and metadata, plus source PDFs when applicable.
- Notein uses AndroidX Ink 1.0.0 exactly: its arm64 `libink.so` matches the Google Maven artifact in size and ELF Build ID.
- Windows desktop port project `Notein-win` was initialized as a .NET 10 WPF desktop application (`Notein-win/src/Notein-win/`) with matching xUnit test suite (`Notein-win/tests/Notein-win.Tests/`). 15/15 unit tests pass and the solution builds cleanly with 0 warnings.
- The `Notein-win` frontend ports the complete dark OneNote-desktop workspace: 32px vertical rail, black multi-notebook section pane with arbitrary recursive nesting, dual-mode gray page pane (`本层` and `递归`), full ribbon tabs (`开始`, `插入`, `绘图`, `视图`), full-screen Backstage (`文件`), interactive multi-layer canvas (background grids/dots/rules, insert objects with dark inversion, rich text boxes, red tags, ink strokes with pressure taper, whole-stroke eraser, and lasso transform handles), tool flyouts, and full-page mode floating toolbar.
- Android workspace prototype v0.5.0 passes unit tests, debug build, and lint as `com.meta.inklab.debug`. It has no Internet/broad storage permission and remains separate from the untouched Notein package. The final build installed successfully and cold-launched in 833 ms; device screenshots verified the icon-only Insert ribbon, split/full-page composition, transient focus frame, center rule, and two-page PDF import. Physical stylus-hover tooltip and subjective pinch feel still require manual feedback.
- The installed lab uses AndroidX Ink wet/dry authoring, motion prediction, one adjustable fountain-pen stroke, prediction toggle, undo, and clear. Controls are width 0.1–2.0 mm, pressure 0–400%, and ink flow 0–200%, with Notein-observed defaults 0.4 mm / 200% / 100%.
- The current source adds default two-finger pan/pinch zoom, a stationary-dwell pressure whole-stroke eraser, and an unbound Xiaomi shortcut interface. Completed ink applies the viewport inside `ViewStrokeRenderer` so screen-space antialiasing remains sharp while zoomed. Tests, build, and lint pass; the corrected APK was installed and cold-launched in 503 ms. Device capture proves the pen exposes `NVTCapacitivePen` plus `Xiaomi Smart Pen Keyboard`; its two shortcut keys send PageUp/PageDown.
- Workspace v0.5.0 has compact OneNote-desktop-inspired dark chrome, multiple notebooks, recursively nestable sections, separate section/page panes with settings-owned `本层` and `递归` page modes, per-tool flyouts, add/delete tool presets, logical black/blue/red pens, a translucent highlighter, explicit whole-stroke eraser, functional lasso selection/delete/move/scale, and working Start/Insert/View ribbons. Search and pixel erasing remain labeled shells.
- The persistent vertical rail independently toggles the black notebook/section pane and gray page pane. The sole section-add button is in the black-pane header: it adds beneath a focused section or at notebook root after blank-space focus clearing. The black pane never renders pages, and the gray pane never renders child-section navigation nodes.
- `文件` opens a full-screen dark Backstage-style notebook/options surface. Prediction and direct-versus-recursive page display live in Options rather than the main workspace. The compact icon-only drawing ribbon uses physical-pixel hairline separators, opens a tool flyout only on the second click of an already selected tool, and leaves the canvas free of permanent page/tool/zoom overlays.
- The panorama hand is functional: selected hand mode enables one-finger panning, selecting it again or choosing a drawing tool exits the mode, and the default remains two-finger pan/pinch with inert single-finger touch. Tablet visual QA confirms the selected state and the expected grid displacement after a 200 x 130 px one-finger drag.
- The navigation rail is 32 dp and its centered section/page controls show a compact selected frame while their pane is expanded. Normal canvas chrome exposes search plus enter-full-page; full-page mode replaces all normal chrome with a centered floating drawing toolbox and pen/search/exit controls. Only the full-page pen toggles that floating toolbox.
- The drawing toolbox now includes a cursor mode that places a session-only transparent text editor, shows its caret, and requests the Android keyboard. Device input-method state confirmed `mInputShown=true`. The sample library intentionally omits copied `更多笔记本`, `快速笔记`, and quick-notebook entries.
- Pressure-eraser stylus lift now unconditionally clears the visible eraser circle as it exits temporary erase mode.
- The `开始` ribbon provides working clipboard, rich-text character/paragraph formatting, styles, and pure-red page tags. Tags use a one-shot choose-then-place interaction and tag search recenters the document on a chosen result.
- Options and its quick-toolbar configuration page are full-screen. Any implemented Start or drawing command can be added to or removed from the horizontally scrollable full-page toolbar.
- Pane type headings are removed. The black-pane `+` offers notebook or section creation; notebook rows are focusable so a section can be added at notebook root, while a focused section receives the new child section.
- Tags can be selected by direct tap or lasso, transformed with a shared selection frame, and removed by the whole-stroke eraser. Cursor mode routes only finger input to text; stylus contact immediately authors with the most recently selected pen without disabling finger text mode.
- Selecting a notebook in the black pane clears section focus and updates the gray pane: in direct/current-section mode the page list is empty and page addition is grayed out/disabled, while in recursive mode the gray pane displays all sections, nested sub-sections, and pages across the entire notebook.
- Section and page rows now separate two long-press outcomes: release without dragging opens a real management menu, while moving beyond touch slop starts manual ordering and release performs only the drop. Menus provide rename/delete, same-notebook move/copy, links, section merge/export/new-child, and page cut/copy/paste; hierarchy/order persist, copied pages retain embedded assets, and deletion removes page-owned data after confirmation. The navigation-management build passes 78/78 unit tests, debug assembly, and lint. It was installed through ADB at `192.168.31.243:45337`; both menus were visually verified, drag release showed no menu, and reordered pages plus restored section hierarchy survived cold restarts.
- User settings are persisted across app restarts and updates in `SharedPreferences`: tool presets (widths, pressure, flow, opacity, colors, eraser radius, added/deleted tools, selected tool), pressure-eraser dwell time (`dwellMillis`) and pressure threshold (`pressureThreshold`), sidebar visibility (`sectionPaneVisible`, `pagePaneVisible`), page pane mode (`CURRENT_SECTION` vs `RECURSIVE`), and active notebook/section/page selection.
- Pressure eraser's press dwell time (50–500 ms) and pressure threshold (40–100%) are directly tunable in both the eraser tool and pen tool dropdown flyouts.
- The working icon-only View ribbon now provides normal/full-page and independent split-view modes, 50/75/100/125/150/200/400/800% presets and step controls, a persistent lock that blocks only finger-pinch scaling while keeping two-finger translation and ribbon zoom actions active, 100% reset, page-width fit, the shared baseline menu, page colors, unbounded or automatically growing A4 paper, optional document-surface page titles, and light/dark page backgrounds. Split slots keep independent navigation/page/view session state, sidebars follow the focused slot, and exit keeps the right slot. Page appearance and baseline settings persist per page; logical black ink and default canvas text remap with the background.
- The add-page dialog has shared page/PDF tabs. Normal pages use the same paper/color/baseline catalogs as the View ribbon and remember the last successful configuration. PDF import creates one real annotated page per source page at the original PDF point size, adds a muted gray-pane `PDF` suffix, retains original/rendered assets, and preserves source MediaBox dimensions in layered export.
- Finite page surfaces no longer mask overflow content; text, ink, tags, and insert objects remain visible and movable outside resized paper over a `#0A0A0A` workspace.
- Full-page mode uses a content-sized configurable floating toolbar and replaces the hidden black navigation rail with two standalone floating pane buttons.
- Insert commands use proper Fluent vectors with no visible labels; delayed hover labels are app-rendered as white text on `#3A3A3A`, avoiding the platform light tooltip.
- Two-finger transform input waits for platform-scaled pan/span slop, then locks the pointer sequence to pan or scale. The installed protected Notein APK exposes `NoteinZoomHandle` only as a reference, so exact Notein thresholds remain unavailable and are not claimed.
- v0.5.0's first gesture-arbiter build had a self-recursive transform-end helper and crashed with `StackOverflowError` on pause/pointer-up. The helper now explicitly clears the activity flag and cancels the arbiter, with cancel/restart behavior covered by a unit regression test.
- The Insert ribbon now implements editable tables, embedded attachments, PDF printouts rendered as one image per page, first-sheet spreadsheet previews with external Excel editing, batch image insertion, built-in/user sticker presets, and note-only links. Inserted objects and original assets persist per page in app-private storage.
- Image and PDF-printout imports have independent `询问 / 自动反色 / 保持原图` options. The view-only filter follows `invert(89.4118%) hue-rotate(180deg)` so white maps exactly to the `#1b1b1b` paper; export always uses original image pixels.
- Export now provides a current-page PDF with Optional Content Group layers (`页面背景`, `插入内容`, `墨迹与文字`) and an active-notebook `.mjn` ZIP containing the manifest, page JSON, original embedded assets, and current-page light-theme layer snapshots. Export uses white paper and restores logical black ink/text to black while keeping colored ink unchanged.
- Static native inspection proves a Xiaomi-only prediction route in this Notein build: its JNI facade loads Xiaomi's `/system_ext/lib64/libtflite_interface_wrap.touch.so` and the bundled `assets/predict/estimate.hdb` model. An isolated probe succeeded after declaring that public native library: availability `true`, model init `0`, and one raw inference returned two floats. Xiaomi's official Pencil Engine SDK is the legitimate product integration route; do not ship Notein's model.
- Design records:
  - `docs/design/sync-architecture.md` (read before synchronization or note-format work).
  - `docs/design/handwriting-engine.md` (read before ink-input, brush, rendering, or tablet-lab work).
  - `docs/design/input-gestures.md` (read before shortcut, gesture arbitration, viewport, or pressure-eraser work).
  - `docs/design/workspace-ui.md` (read before notebook/section/page navigation, tool configuration, or workspace styling).
  - `docs/design/insert-and-export.md` (read before insert objects/assets, image/PDF view filters, note links, or export formats).

# Active Work

- [x] Establish ADB access and identify the exact installed package/version.
- [x] Capture first-pass APK/runtime/storage architecture evidence.
- [x] Decompile resources far enough to locate the supported local-backup path and distinguish it from cloud sync/backup.
- [x] Inspect representative user-owned external blobs without exposing note content; sampled UUID blobs are PNG previews.
- [x] Determine initial feasibility: direct source port is not practical; a clean-room companion is conditional on the supported backup format.
- [x] Write the synchronization design boundary before implementation.
- [x] Create and inspect an app-supported full local backup without modifying Notein's live private data.
- [x] Prove a read-only structural parser for the outer backup, per-note containers, Room schema, and stroke record families.
- [x] Identify the exact AndroidX Ink native version and persisted `notein-fountain-v1` pressure/speed settings.
- [x] Build, lint, install, and runtime-check a separate no-network Android handwriting lab for real stylus A/B testing.
- [x] Capture the Xiaomi pen's actual input devices/keycodes and implement the unbound shortcut protocol boundary.
- [x] Implement and unit-test shared-coordinate two-finger pan/zoom and a stationary-dwell temporary pressure eraser.
- [x] Install the corrected pressure-eraser build after wireless ADB moved to port 33039.
- [x] Correct completed-stroke transform ordering so zoom no longer enlarges the 1x antialias fringe.
- [x] Prove that the isolated Xiaomi prediction facade/model initializes and emits a two-float raw inference result.
- [x] Build the first OneNote-desktop-inspired workspace UI with nested sections and working pen/highlighter/eraser/lasso tools.
- [x] Implement both user-specified page-pane modes without mixing pages into the black section pane.
- [x] Install and visually inspect v0.2.0 on the tablet after rediscovering its changing wireless-ADB port.
- [x] Replace the single-tree shell with a multi-notebook section tree, focus-relative section creation, and independently collapsible section/page panes.
- [x] Move prediction and page traversal modes into a full-screen Backstage/options flow while keeping their behavior live.
- [x] Match the PC OneNote drawing-ribbon density and grouping, remove permanent canvas overlays, and add working panorama-hand input arbitration.
- [x] Add context-specific canvas search/full-page controls, a full-page-only toolbox visibility button, and a centered floating drawing toolbox.
- [x] Add session-only text caret/keyboard interaction, compact selected rail controls, and remove quick-notes sample chrome.
- [x] Clear the stationary pressure-eraser circle on stylus lift.
- [x] Implement the working Start ribbon, one-shot canvas tags, full-screen options, and configurable full-page quick toolbar.
- [x] Remove redundant pane headings and add notebook-versus-section creation with notebook-level focus.
- [x] Make tags selectable/erasable, implement lasso drag and corner scaling, and separate finger text input from immediate stylus pen input.
- [x] Fix gray page pane behavior on notebook selection: empty with disabled `+` in non-recursive mode, and full-notebook hierarchy in recursive mode.
- [x] Add persistent section/page long-press management menus, confirmed deletion, asset-aware copy, scoped section export, and mutually exclusive release-menu versus drag-to-sort gestures.
- [x] Persist tool shelf presets, sidebar visibility, page modes, active selections, and pressure eraser parameters across app updates/restarts.
- [x] Add adjustable press dwell time and pressure threshold controls to the eraser tool dropdown.
- [x] Add insert-space and background format (rule lines/grid styles) tool group to the right of the panorama hand in the drawing ribbon.
- [x] Add the functional icon-only View ribbon and reuse the drawing ribbon's completed baseline/background-format implementation.
- [x] Add the page-pane creation dialog with remembered unbounded/A4, baseline, and page-color configuration; A4 pages append Word-style sheets as content approaches the bottom.
- [x] Implement the working Insert ribbon, persistent embedded asset boundary, independent image/PDF view filters, layered light-theme PDF export, and private notebook package export.
- [x] Merge View/page-template work with Insert/export, add PDF-per-page import/export, independent focused split views, visible paper overflow, and adaptive full-page controls.
- [x] Initialize and port Notein-win Windows desktop frontend (.NET 10 WPF MVVM) with OneNote-style dark workspace, ribbons, recursive section navigation, dual-mode page pane, multi-layer canvas, and full xUnit test suite.
- [ ] Collect physical feedback for stationary pressure erasing and recover its observable Notein threshold/dwell mapping.
- [ ] Collect the user's unplugged subjective feedback on the single adjustable stroke, especially nib alignment, taper, and pressure/ink-flow response.
- [ ] Prove a read-only Notein stroke renderer before any write-back experiment.

# Build / Run / Test

- Windows desktop client (`Notein-win`):
  - Build solution: `dotnet build Notein-win\Notein-win.slnx` or `cd Notein-win; dotnet build`.
  - Run unit tests: `cd Notein-win; dotnet test`.
  - Launch desktop app: `cd Notein-win\src\Notein-win; dotnet run`.
- ADB binary: `C:\Users\Meta\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- Verify device: `adb devices -l`.
- Installed package path: `adb shell pm path com.orion.notein`.
- APK inspection tools currently available: Android SDK `apkanalyzer`, Java 17, and Scoop-installed Rizin 0.9.1. `jadx` is not globally installed; use a temporary portable copy if needed.
- Keep device probes read-only until a tested backup/restore strategy exists. Do not enable Notein cloud sync or write into its live data directory during format discovery.
- Initial handwriting lab package must remain `com.meta.inklab`, request no network/storage permission, and store no durable user content.
- Lab build and lint: from `android`, run `.\gradlew.bat :app:assembleDebug :app:lintDebug`.
- Workspace unit tests: from `android`, run `.\gradlew.bat :app:testDebugUnitTest`; navigation tests cover unlimited section depth and both page-pane modes, while tool tests cover required presets and add/delete behavior.
- View tests cover explicit anchor-preserving zoom, page-width fitting, A4 auto-growth thresholds, legacy paper migration, and page-view preference JSON round trips.
- Debug APK: `android\app\build\outputs\apk\debug\app-debug.apk`; installed debug package/activity: `com.meta.inklab.debug/com.meta.inklab.MainActivity`.
- Tablet install: direct `adb install -r -t -d` succeeds over wireless ADB and immediately launches via `am start`.

# Durable Lessons

- Notein metadata and document blobs are split across Android app-private and app-specific external storage. Syncthing of only `/Android/data/com.orion.notein/files` is not yet proven complete or safe.
- The installed China build differs from the global Play package (`com.orion.notein.global`); do not assume global-package paths or backup behavior apply.
- Launching Notein can immediately activate configured Huawei Drive/analytics components. Keep it force-stopped during static work and avoid launches that are not needed for a deliberate local-backup experiment.
- Notein 2.0.10.0 uses the exact AndroidX Ink 1.0.0 arm64 native library. Reproduce the public Ink input/rendering pipeline and clean-room brush behavior. Never instrument or replace the live installed Notein package; deeper unpacking, if needed, belongs in a separately signed isolated environment.
- The application manifest points to Baidu Protect's `com.sagittarius.v6.StubApplication`; defined-only DEX inspection contains the shell but not Notein business classes. Decompiled resource constants and native/JNI paths are exact evidence, while the protected pressure state machine still needs runtime observation or an isolated unpacking environment.
- Xiaomi publishes a Pencil Engine SDK that accepts current and historical MotionEvent points, takes the display refresh rate, and returns predicted points. Prefer that supported/authenticated SDK for Xiaomi-specific production acceleration, with AndroidX `MotionEventPredictor` as the portable fallback.
- The current new-stroke population consistently uses `notein-fountain-v1`, `pressure_correct=2.0`, `speed_correct=1.0`, and epsilon `0.01`; keep these as versioned evidence, not as proof of the full private brush graph.
- Notein's displayed percentages map directly to persisted correction values by dividing by 100. Preserve that boundary mapping, but do not call the lab's downstream curve Notein's curve until the runtime brush graph is recovered.
- This tablet's input becomes unstable while charging. Test brush behavior unplugged and keep charger-induced digitizer noise separate from smoothing or prediction conclusions.
- The Xiaomi pen buttons are Bluetooth keyboard PageUp/PageDown events from the exact device name `Xiaomi Smart Pen Keyboard`; never globally intercept those keycodes from ordinary keyboards.
- Notein resources prove that hold-to-erase is default-enabled and the visible default sensitivity is 20%, but protected code hides how that value maps to normalized pressure plus its exact dwell/slop constants. Keep the lab threshold explicit and tunable rather than presenting 78% / 140 ms / 2 dp as recovered constants.
- Android 15 edge-to-edge enforcement can put a custom rail under system bars. Apply actual system-bar insets and verify on the real portrait tablet, not only with a successful build.
- `ViewStrokeRenderer` captures the canvas transform when `drawWithStrokes` starts. Apply viewport
  transforms inside its drawing block; applying zoom before entry makes its screen-space
  antialiasing omit the zoom even though Canvas still scales the geometry, producing soft edges.
- Keep wireless-debugging state and connection-port discovery separate: this tablet normally leaves wireless debugging enabled, while the ADB port is dynamic. If the user supplies a current endpoint, use it directly for that session; otherwise check existing connections and retry mDNS discovery. Never hard-code the last port, and never infer that the tablet is offline or wireless debugging is disabled merely because mDNS returned no service.
- A custom dark view hierarchy still inherits the application base theme for system menus and dialogs. Keep the Android base theme dark as well, or `PopupMenu` surfaces will remain light.
- Keep section and page pane data types separate. The black pane recursively flattens only `SectionNode`; the gray pane either lists direct `NotePage` items or a separate recursive stream of section headers plus owned pages. A deep-page selection must not silently change the black-pane root.
- Keep text input modality filtering at the text-layer dispatch boundary. Changing only the selected toolbar mode cannot prevent an enabled `EditText` tree from consuming stylus events.
- Render and hit-test a mixed ink/tag selection through one top-level overlay, but keep the underlying content transforms in their owning layers so selection chrome never becomes document content.
- Preferences load before the view hierarchy exists. Restore canvas-affecting values into ordinary state fields, then apply them while building the corresponding view; never dereference a `lateinit` canvas view from `loadPreferences()`.
- Treat A4 as a paginated document policy, not one fixed canvas rectangle. Grow the stored sheet count before content reaches the last sheet's bottom, keep explicit inter-sheet gaps, and never shrink automatically while editing.
