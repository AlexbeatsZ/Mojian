# Goal

- Bring 墨笺 to native iOS/iPadOS with phone/tablet layouts, editable notes and configurable Apple Pencil side gestures.
- Build Mojian as a persistent Windows/Android note application with one shared folder format,
  editable handwriting and Syncthing synchronization. Existing Notein/OneNote notes are imported
  into this format; the initial three-device library is authoritative on OMEN.
- Prioritize a clean-room handwriting engine that reproduces Notein's pen character and low apparent latency, because both the compatibility route and an independent product need it.
- Preserve the user's existing Notein data. Reverse engineering is for interoperability with the user's own notes; do not publish proprietary app code or keys.

# Current State

- On 2026-10-12 the Windows panorama Hand / middle-drag pan lag was traced to a full scene
  re-rasterization per pan sample (real-window GPU: 22-31 ms/frame at 50% on 1-3k stroke pages).
  Pans now move a temporary overscanned `BitmapCache` of `SceneLayer` (about 4 ms/frame), re-render
  once per half view of travel and restore vector rendering on release. Windows 163 tests pass;
  the sole Release EXE was republished and reopened. This WPF-specific fix does not change Android.
  See `docs/design/writing-performance-and-durability.md`.

- On 2026-10-12 Android two-finger tap/short page drag was found to pop the canvas context menu
  afterwards: `CanvasInputHost` swallowed the rest of the sequence without cancelling the child
  that received the first finger, so `SelectionOverlayView`'s long-press timer (system 300 ms on
  both devices) still fired after navigation ended. The host now sends `ACTION_CANCEL` on any
  mid-sequence takeover (`ChildStreamHandoff`), the overlay stops the timer on every cancel, and a
  one-finger move interrupted by a second finger is finished/saved. Android 165 tests, APK and lint
  (0 errors, 130 warnings) pass; installed in place on tablet and K70 and both launched. Physical two-finger acceptance is pending; the user chose to keep the system long-press timeout. No Mojian Android crash was
  in either device's logcat; Android saves use POSIX rename and catch refused writes with a toast.

- On 2026-10-12 the Windows EXE crashed repeatedly after Syncthing deployment: `File.Replace` of
  `library.json`/page JSON hit a sharing violation from Syncthing's hasher during
  `OnLeftCanvasFocus -> SaveDocument`. `AtomicWrite` now retries transient lock errors
  (32/33/1175-1177, access denied) for up to 3 s; synchronous save failures set
  `保存失败，请重试` and abort the navigation; page-metadata saves run at idle; a dispatcher
  handler logs to `%LOCALAPPDATA%\Mojian\logs\crash.log` instead of terminating. Windows 162 tests
  pass; the sole Release EXE was republished and reopened. Android is unaffected (POSIX atomic rename has no sharing locks).

- On 2026-10-12 OMEN's complete five-notebook / 652-page workspace seeded Syncthing `Mojian`
  (1,793 files / 5,285,164,934 bytes). OMEN, Redmi K70 and Xiaomi Pad 6 Pro now use send/receive;
  Android's folder is `/storage/emulated/0/Documents/Mojian`. Both applications are version
  0.6.0; Android remains `com.meta.inklab.debug` / 7 and was updated in place with user approval.
  Android now reads/writes the desktop v2 canonical store in 96-DPI units, preserves imported
  paths/layers/unknown metadata, and durably saves ordinary handwriting, tags and text on a
  serial worker. Both clients refresh incoming changes while idle and reject stale saves.
  Actual phone -> tablet -> OMEN -> phone/tablet edits and cold reopen passed on a disposable
  page, which was removed after acceptance. The current library retains five notebooks / 653
  pages, including a source page added during deployment. Original homework ink, objects and
  tags are identical to the pre-acceptance data (1,110 strokes / 26,821 points). Both devices
  read/render the 59.6-MB imported page with 10,245 strokes / 402,710 points after nullable PDF,
  single-parse and streaming-content comparison fixes; cold native checks take about 15-16 s.
  Windows 161 and Android 162 tests pass; APK/test-APK/lint builds pass (0 errors, 130 warnings).
  Both installed APKs include the legacy pen-color and two-finger/right-button fixes. Native
  color checks pass on both devices; gesture instrumentation remains blocked by Xiaomi's test
  APK installation restriction. Existing Syncthing identities and other folders remain. The database
  minimum was changed from 1% to 1 GB when C: had 2.1 GiB free; a separate task freed space.
  See `docs/design/live-note-sync.md` for format, permission, concurrency and runtime evidence.

- On 2026-10-08 the shared Export/Print integration was delivered with page/section/notebook
  scopes, physical pagination, paper/orientation/margins/ranges, native print dialogs and previews.
  Export retains current appearance; dark-page print converts white writing and neutral black
  paper, permits per-image inversion, and optionally hides every background pattern in either
  theme. The unified build includes native/SVG/PNG copy and two-column backgrounds. Windows
  158/158 tests (including the unchanged 1,110-stroke / 26,821-point homework as two vector pages)
  and Android 147/147 tests, APK/lint/test-APK builds pass (0 lint errors, 116 warnings).
  The sole Windows EXE (21:29 SGT) is reopened in the same five-notebook, 653-page workspace;
  the current Android APK (21:13 SGT) is refreshed. The tablet is connected, but its installed
  process retains session-only freehand: installation awaits the user's session-loss choice.
  Native Android output tests are compiled, not run. Read `docs/design/export-and-print.md`.

- Native Apple client 0.1.0 is in `ios/` (UIKit, iOS 17+, universal iPhone/iPad). It includes
  editable pressure ink/shapes, direct touch selection, eight-handle resize, Undo/Redo, persistent
  hierarchy/assets/text/tables, Files `.mjn` exchange, vector PDF/PNG output and native printing.
  Phone/narrow iPad uses navigation sheets; wide iPad uses two panes. Pencil 2/Pro double-tap and
  Pro squeeze have separate persisted actions; third-party pen input requires explicitly mapped
  keyboard characters. Final Release simulator and unsigned arm64 device builds passed with
  Xcode 27.0 / iOS 27.0; 19/19 portable tests pass. Actual simulator checks exercised iPhone
  finger ink/Undo/Redo/page creation and iPad portrait/landscape/settings. Saved ink and Pencil
  settings were inspected; Release restart retained both. Native PDF sharing and independent A4
  vector/zero-image rendering checks passed, as did a configured keyboard event switching Eraser/Pen.
  No physical iOS device/development Team is available, so no signed IPA
  or physical Pencil acceptance is claimed. Complete parity gaps are listed in
  `docs/design/ios-client.md`; Android/Windows outputs and live notes were not modified.

- On 2026-10-08 both clients gained `标准双栏横线`, `窄双栏横线`, and `宽双栏横线` in
  the background menus and new-page choosers. One vertical rule follows the actual paper center,
  stays independent of viewport/export crop, and clips to each A4 sheet. Boundless growth updates
  the center. Persisted page settings and shared `.mjn` names retain the format; print-only hidden
  backgrounds also hide its divider. Existing numeric Windows format values remain unchanged.
  Isolated Windows 151/151 and Android 143/143, APK/lint/test-APK
  builds passed (0 lint errors, 113 warnings). Real WPF menu, save/reopen and new-page chooser
  checks passed; Android native checks were compiled, not run. Tablet ADB has since returned;
  installation remains coordinated with delivery to preserve its running handwriting session.
  The unified current APK includes all three formats (147 merged Android unit tests pass).
  Final unified Windows EXE delivery includes all three formats. Read `docs/design/workspace-ui.md`.

- On 2026-10-08 output colors were separated from page editing on both clients. Dark-page image
  insertion defaults to inversion without a prompt; light-page insertion never asks/inverts and
  hides its policy controls. Image menus toggle persisted inversion in either theme. Visual export
  and selection SVG/PNG retain current appearance; only dark-page printing converts white/logical
  white ink/text to black and neutral black paper to white. Print image IDs toggle displayed
  inversion from original assets; optional `隐藏背景格式` removes patterns from print/preview
  in either theme without mutating page settings. Root Windows 144/144 and Android 142/142 plus
  APK/lint/test-APK builds pass (0 lint errors, 113 warnings); native Android output tests are compiled, not run on the live
  tablet. Final output UI and delivery are included in the unified build above.
  Read `docs/design/insert-and-export.md`.

- On 2026-10-08 both clients gained native editable selection copy plus separate SVG and PNG copy
  options in Home and content menus. Ctrl+C remains native copy; SVG/PNG are configurable shortcut
  actions with no default binding. Private clipboard ownership prevents old system images or older
  internal snapshots from winning paste. Native copies capture independent content and asset bytes;
  Windows imported scene ordering and Android shape identities are retained safely. Initial Windows
  141/141 and Android 139/139 tests, APK/integration-APK assembly and lint (0 errors, 112 warnings)
  passed; actual WPF command/menu checks passed. Windows now pairs its private format marker with
  the Win32 clipboard generation: custom OLE payloads were observed becoming empty streams.
  External changes and history restoration cannot revive an older native snapshot. The final
  shared delivery includes current-display output. Read the
  Selection clipboard section of `docs/design/insert-and-export.md` for clipboard lifetime, native
  editability and SVG boundaries.

- On 2026-10-08 both clients' selection handles gained an interior body-move region for tiny/narrow
  images, mouse/touch-specific target radii, aspect-preserving corner resize and single-axis edge
  resize. Resize derives from starting content geometry and pointer displacement, keeping the
  opposite anchor fixed without off-center/padded-frame jumps. Android clamps at the shared frame
  rather than individual image dimensions. Isolated Windows 136/136 tests (including actual canvas
  move/resize, Undo and save/reload) and Android 137/137 tests, debug/test-APK builds and lint
  (0 errors, 110 warnings) passed. Both current deliverables are refreshed; the sole Windows EXE
  reopened in the same five-notebook workspace. No tablet ADB/mDNS endpoint was available, so
  Android native/physical interaction remains unverified. See `docs/design/input-gestures.md` and
  `docs/design/insert-and-export.md`.

- On 2026-10-08 the user's `Chemistry / Chemical Kinetics / 作业 1` (edited 09:09 SGT)
  was re-exported as two occupied A4 pages with vector black ink on white paper after the user
  rejected the first low-resolution raster result. All 1,110 strokes / 26,821 points remain in
  the source; the final PDF has zero image objects and passed ordinary/detail visual rendering.
  Both PDF routes now preserve native ink outlines instead of screenshot layers. Windows writes
  native curves/glyph paths, transforms, opacity, images and valid Unicode OCG metadata; source
  scenes preserve canonical interleaving. Android uses native PdfDocument plus Ink boundary paths
  and copied light-theme text layouts (no OCG toggle; integer-point MediaBox). Windows 132/132
  tests pass. Android 134/134 unit tests, build/lint (0 errors, 110 warnings) and separate integration-APK build pass; tablet runtime
  acceptance remains pending to preserve its session-bound live product. Read
  `docs/design/insert-and-export.md` before PDF drawing/serialization changes. Both deliverables are refreshed; the new Windows EXE is reopened in the same five-notebook workspace.

- On 2026-10-08 the user's follow-up confirmed that the previous EXE was running but low zoom
  remained slow. Actual software rasterization exposed the old cache's roughly 14,000 retained
  line commands. Fountain ink now uses one filled vector geometry per stroke, and ordinary wet
  ink has its own visual without rebuilding the completed scene on each sample. Source-layer/tag
  occlusion retains its canonical path. The same-run 50% reference improved from about 56 to 22 ms
  (software snapshot timing, not physical pen latency); 30-sample scene reuse and handoff pixels
  pass. Windows 128/128 tests and a zero-warning build passed. The sole EXE was republished and
  opened in the same five-notebook workspace; all 584 strokes / 14,396 points were identical after
  normal closing and replacement. The user declined historical note recovery. Physical low-zoom
  pen acceptance remains pending. This WPF rendering repair does not change Android.

- On 2026-10-08 the Windows small-page/pen-hover slowdown and save reliability defects were traced
  using the user's 584-stroke / 14,396-point page. Hover now owns a separate visual; completed ink
  retains frozen vector drawings; ordinary ink Undo is append-based; JSON/page/library autosave
  runs on a serial background worker with close/navigation flush and retry. Same-page split slots
  share one document, and the desktop rejects a second writer process. Durable atomic JSON writes
  retain one `.previous` revision and recover valid prior data; corrupt/unreadable data cannot
  silently become empty pages or switch workspaces. The user declined further historical recovery.
  See `docs/design/writing-performance-and-durability.md` for measured evidence, source
  reference boundaries, platform scope and recovery candidates. Windows 125/125 tests passed;
  cached-versus-original stroke pixels and pointer-only redraw checks passed. The sole self-contained
  EXE was republished and normally reopened in the same five-notebook workspace; normal close/reopen
  retained all 584 strokes / 14,396 points, and a second launch exited into the existing native window.
  Physical pen feel remains user acceptance. Android was inspected but this WPF/desktop-store
  incident does not change its separate session-boundary freehand model.

- On 2026-10-08 both clients' canvas zoom increased from 50–800% to 50–6400% (0.5–64x). `ViewportState` owns each client's limits and the 50/75/100/125/150/200/400/800/1600/3200/6400% catalog; Android's preset shortcuts reuse that catalog. Gesture/menu/button zoom and restored viewports use the expanded bounds. Windows displays `6400%` without a thousands separator so it fits the existing control. Windows 113/113 and Android 134/134 tests, Android debug build and lint (zero errors, 110 warnings) passed. The real isolated WPF window exercised the 6400% menu action/status, and the canvas raster check retained a thin editable vector at 64x. Both deliverables were refreshed, build intermediates cleaned, and the current Windows EXE reopened in the existing five-notebook workspace. No tablet was connected and two mDNS checks returned no endpoint; this APK has not been installed or physically tested on the tablet.

- Pressure erasing now has an eraser-owned accumulated-contact travel limit: default 1 mm, adjustable 0.1–10 mm in 0.1 mm steps. Real document-space path length includes low-pressure movement and historical points; strictly exceeding the limit cancels/latches out activation until lift/cancel, while an already activated/manual eraser remains movable. Old presets gain the default. Windows 106/106 tests (including real canvas and eraser panel persistence) and Android 130/130 plus build/lint passed on 2026-10-07. Both current deliverables were refreshed, the tablet update cold-launched in 931 ms, and the Windows five-notebook workspace retained. Physical pen acceptance remains pending; see `docs/design/input-gestures.md` section 10.

- 2026-10-07 UI polish reviewed and delivered: navigation-tree disclosure arrows use one Fluent `chevron_right` vector at 16 px/dp, rotated 90° when expanded, instead of swapping font glyphs. Childless sections retain their 24 px/dp slot and pass clicks to the row. Dropdown and selected-tool indicators use the shared filled `caret_down` vector; Android submenu arrows use the same chevron. All 62 Android vectors regenerate the Windows icon dictionary without hard-coded chevron triangles. Windows 106/106 tests and isolated WPF screenshots of the drawing/home ribbons and Page/PDF dialog passed; Android 130/130 tests, debug APK assembly and lint passed (zero lint errors; 110 warnings, including dynamic icon lookup false positives). The sole Release EXE and current APK were refreshed, and the Windows release reopened in the existing workspace. This build has not been installed on the tablet.

- Both clients now provide seven editable primitives (line/arrow/circle/ellipse/rectangle/triangle/diamond) and pen/highlighter hold-to-straighten. Shape settings own the shared enabled switch and 200–2000 ms delay (default 1000 ms). The current Notein 2.1.11.0 resource guide confirms a one-second hold, but protected recognition constants were not recovered. Android persists generated `shape-v1` strokes in page JSON; Windows preserves them through its store and the shared `.mjn` page payload. On 2026-10-07 Windows 103/103 and Android 126/126 tests, Android lint/build, isolated real WPF shape panel/canvas/export checks and the tablet's seven-shape native probe passed. The sole Release EXE and current APK were refreshed; both clients launched and the five live Windows notebooks retained. Read `docs/design/shapes-and-straightening.md`. Physical stylus acceptance remains pending.

- The 2026-10-02 native reimport is active in `C:\Users\Meta\Documents\Mojian\_migration\native-20261002\workspace`: five notebooks, 681 pages (635 editable imported pages, 44 source/recovery pages, two preserved local pages), 541,419 per-stroke vectors, 966 independent objects and 102 text items. All referenced assets validate; OneNote's 112 matched printouts rerender from recovered original PDFs. Source colors, Notein layer order/visibility and mixed image/ink/text ordering persist. Per the user's instruction, 126 pages from Notein notes lacking original PDFs were deleted, including 57 annotation pages and 69 placeholders; use `--skip-missing-notein-pdfs` on future rebuilds. Old `C:\Users\Meta\Documents\Mojian` and source backups remain for recovery. Notein contours are reconstructed. See `docs/design/data-migration.md`; Android has no equivalent source-backup importer/native-scene renderer.

- Repository was initialized with feasibility commit `817787a`; `master` now tracks the private GitHub remote `https://github.com/AlexbeatsZ/Mojian`.
- The tablet keeps Android wireless debugging enabled (Xiaomi `23046RP50C`, Android 15 / API 35, arm64), but its ADB connection port changes. A user-supplied endpoint such as the last verified `192.168.31.243:45337` is current-session information only: connect to it immediately when supplied, never treat it as a fixed port, and never reuse an older recorded port without verification. When no current port is available, check existing `adb devices` connections and retry `adb mdns services`; an empty mDNS result does not mean the tablet is offline or wireless debugging is disabled.
- Installed China package, rechecked 2026-10-07: `com.orion.notein`, `v2.1.11.0` (`versionCode=351`, `minSdk=26`, `targetSdk=35`), single 129,403,508-byte APK. Earlier native/backup investigations refer to 2.0.10.0.
- APK is non-debuggable and app-private `/data/user/0/com.orion.notein` cannot be read with `run-as`.
- App-specific external files are readable at `/sdcard/Android/data/com.orion.notein/files`; current snapshot is about 256 MiB / 5,638 files, mainly UUID-named blobs and PNGs.
- Static scan shows a native Android/Kotlin/Java app, not Flutter/React Native. It includes arm64-only native libraries such as `libink.so`, PDFium, OpenCV, SQLiteX, and Baidu Protect. Xiaomi CloudKit database/file sync providers are bundled.
- Runtime resources and logs show that editable data depends on a private `NoteContentDatabaseProvider`; the readable external directory is predominantly PNG previews.
- A user-authorized full local backup was created through Android's save picker as `/sdcard/Download/backup.zip` (1,579,602,577 bytes). Keep the tablet copy; the analysis copy is temporary and must not be committed.
- The backup is an unencrypted ZIP containing 167 per-note `.in` nested ZIPs, two `.template` entries, and one `.tree`; per-note containers include a Room/SQLite v25 database and metadata, plus source PDFs when applicable.
- Notein uses AndroidX Ink 1.0.0 exactly: its arm64 `libink.so` matches the Google Maven artifact in size and ELF Build ID.
- The first `Notein-win` WPF attempt compiled and its 15 model tests passed, but real-window inspection on 2026-08-23 proved it was a non-functional prototype: invalid XAML conversions made Backstage/full-page/pane visibility fail, ribbon tabs did not own separate panels, inserted objects replaced page state, and workspace/ink/text/export were not connected to durable storage. Do not treat that build as a product baseline.
- `Notein-win` is now the persistent Windows edition of the project's own Android app, `墨笺` (`com.meta.inklab`), around `docs/design/windows-desktop.md`: atomic folder-based persistence, real per-page documents/assets, independent desktop navigation state, stylus-aware canvas input, working import/export, a user-selectable Syncthing workspace, and Android-compatible `.mjn` exchange. It is not a Notein Windows client.
- Android `app/src/main` vectors, colors, ribbon order, dimensions, selection states, and preserved multi-color paths are the Windows UI source of truth. ADB screenshots of the installed tablet build were compared against the real WPF window; 62 Android vectors are deterministically converted into `Themes/IconResources.xaml` by `tools/Generate-FluentIconResources.ps1`, so the Windows port no longer substitutes generic icons or clipped fragments.
- On 2026-10-12 the user reported that the Windows window had no visible minimize/maximize/close
  buttons: the former DWM-owned buttons answered hit tests but were covered by the opaque WPF top
  row. The 34 px top row now draws its own caption buttons (OS caption-font glyphs) in normal,
  full-page and Backstage modes; maximize reports `HTMAXBUTTON` for Windows 11 Snap Layouts, and a
  maximized window insets its layout by the sizing frame. Windows 163 tests pass; the sole Release
  EXE was republished/reopened and real maximize/restore was checked. See the Caption buttons section
  of `docs/design/windows-desktop.md`.
- The current Windows solution builds with zero warnings and passes 132/132 tests, including isolated real-window checks, writing/save reliability checks and imported Notein/OneNote canvas movement, erasing, layer occlusion/hit testing, 8x imported-page rendering and a 64x thin-vector check. The migration suite passed 9/9 on 2026-10-02. Physical pen/mouse interaction remains user-owned acceptance. Exactly one self-contained win-x64 build is retained at `mojian/artifacts/release/Mojian.exe`; the old desktop test shortcut was not present on this host during the 2026-10-02 check. Section/page management menus expose the Android operations, including rename/delete, move/copy, merge/section export, links, page cut/copy/paste, copied assets, and page-owned-data deletion.
- On 2026-10-06 both clients gained notebook-row management (`重命名`, `删除`, `新建分区`): Windows right-click and Android long-press/mouse right-click. Confirmed deletion removes the notebook-owned directory, clears split/session references, and cannot be resurrected by late Windows autosave. Deleting the last notebook leaves a fresh empty notebook. Windows menu routing was exercised in a real isolated WPF window; 97/97 Windows and 121/121 Android tests plus Android lint passed. The current Windows EXE was republished/reopened against the unchanged five-notebook workspace; the tablet APK was updated in place over the existing `192.168.31.242:5555` connection and cold-launched in 1,406 ms. Physical Android notebook-menu interaction remains unverified.
- The Windows full-page chrome now uses transient pane state: entering full page closes both panes without changing the normal-workspace preferences, removes the hidden rail's 32 px layout column, keeps the top controls interactive inside `WindowChrome`, and uses raster-checked centered enter/exit glyphs inside complete 34 px buttons.
- Windows Ctrl+middle/stylus-hand drag zoom uses a fixed screen diagonal: down-right enlarges and up-left shrinks regardless of canvas center or starting quadrant, while retaining the contact anchor. Holding middle is the sole trigger: in-air stylus movement immediately pans, while Ctrl changes that same in-air movement to zoom; no nib contact or second click is required. This device's promoted mouse coordinates exclusively drive the in-air phase, then the newest high-resolution stylus point takes over after contact so the motion is never applied twice. Windows no longer draws a visible extrapolated line ahead of the nib dot. The hand tool changes only stylus input; ordinary mouse input keeps the arrow cursor, while a physical stylus in range uses a small screen-space pen dot for pen/highlighter input.
- Android and Windows implement the device-independent direct-input contract in `docs/design/input-gestures.md`: pen state is only Ink/Lasso/Hand; touch and primary mouse normally select/edit or containment-marquee content; only selected content can move/resize; two fingers preempt any pending direct gesture and navigate the page; and canvas double-click/double-tap is reserved. Insert Space is the documented exception: finger or pen drag executes it, pen/mouse hover shows its dashed preview, and mouse-left remains direct-content. One shared dotted eight-handle frame spans ink, tags, text, and inserted objects. Whole-stroke erasing and clear-page do not remove inserted objects or immutable PDF page backgrounds.
- The gray-pane `+` now opens a real dark Page/PDF creation dialog. PDF import creates one immutable-background page per source page, retains the original/rendered assets and source MediaBox, does not replace the remembered normal-page template, and exports Android's `PDF` view settings, PDF manifest metadata, `PDF_PAGE` source kind, background flag, and transform fields in `.mjn`. A 2026-08-24 isolated real-window acceptance created a page, inserted/lassoed/moved a sticker, restarted the Release EXE, proved JSON/asset/tool persistence, and restored the user's original workspace afterward.
- `tools/data-migration` exports OneNote native ISF outlines through the C# decoder, enriches original cache attachments/source printout metadata through small sidecars, and converts Notein SQLite/protobuf inputs into editable contours. Normal migration excludes image fallback sections; `--include-visual-fallbacks` explicitly opts into those recovery copies. The user rejected the older image-like migration; do not use its former page/stroke counts as evidence of native behavior.
- Windows PDF pages retain their original vector PDF plus page number and use the stored PNG only as an immediate placeholder. The canvas asynchronously rerenders from the vector source for current zoom/DPI in 2048--4096 px buckets, fixing the earlier single 144 DPI bitmap stretch. Notein annotated PDF pages with available source PDFs carry the same source link; their annotation strokes remain editable.
- The dense-import performance path is viewport-bounded on both clients. Windows caches stroke bounds, culls offscreen strokes/objects/A4 sheets, writes compact page JSON, and no longer rewrites a whole page after pan/zoom; on the verified 407,230-point page, first render fell from 1,829 ms to 6 ms and repeat render from 1,051 ms to 3--4 ms. Android caches stroke bounds/content bottom, culls offscreen dry ink, and no longer rescans every input point during each viewport update. Export still renders all content.
- Android workspace prototype v0.5.0 passes 114/114 unit tests, debug/test APK assembly, and lint as `com.meta.inklab.debug`. It has no Internet/broad storage permission and remains separate from the untouched Notein package. The 2026-10-02 boundless-canvas build updated the existing tablet package without uninstalling it and cold-launched in 721 ms; physical pen interaction remains unverified. Xiaomi previously rejected installation of the separate instrumentation APK with `INSTALL_FAILED_USER_RESTRICTED`; the target app remained installed and running, so do not claim the new gestures received on-device instrumentation acceptance. Earlier device screenshots verified the icon-only Insert ribbon, split/full-page composition, transient focus frame, center rule, and two-page PDF import.
- The installed lab uses AndroidX Ink wet/dry authoring, motion prediction, one adjustable fountain-pen stroke, prediction toggle, undo, and clear. Controls are width 0.1–2.0 mm, pressure 0–400%, and ink flow 0–200%, with Notein-observed defaults 0.4 mm / 200% / 100%.
- The current source adds default two-finger pan/pinch zoom, a stationary-dwell pressure whole-stroke eraser, and Android-only configurable Xiaomi shortcuts. Each exact-device PageUp/PageDown key has separate single/double/hold bindings; double and hold timing are user-adjustable, while normal external-keyboard PageUp/PageDown remain untouched. Completed ink applies the viewport inside `ViewStrokeRenderer` so screen-space antialiasing remains sharp while zoomed. Device capture proves the pen exposes `NVTCapacitivePen` plus `Xiaomi Smart Pen Keyboard`; its two shortcut keys send PageUp/PageDown.
- Android now imports the desktop mouse contract at the canvas host: right-click release opens the app-owned canvas/selection/object menu, right-drag beyond platform slop performs temporary freeform lasso, middle-drag pans, and Ctrl+middle-drag zooms; a new right/middle press dismisses an open canvas menu and continues the gesture. A Xiaomi pen key whose single binding targets Lasso uses the configurable hold threshold as a partial right-button replacement: short press opens the menu at the latest pen hover/contact point, while contact always draws an immediate preview and a long press commits it without a menu. Pen classification never uses movement distance, and ordinary PageUp/PageDown keyboards remain unmodified.
- Workspace v0.5.0 has compact OneNote-desktop-inspired dark chrome, multiple notebooks, recursively nestable sections, separate section/page panes with settings-owned `本层` and `递归` page modes, per-tool flyouts, protected required defaults plus add-only tool presets, logical black/blue/red pens, a translucent highlighter, explicit whole-stroke eraser, functional lasso selection/delete/move/scale, and working Start/Insert/View ribbons. Tool flyouts expose neither delete-tool nor delete-selection buttons; non-editing Backspace/Delete removes the current mixed selection. Search and pixel erasing remain labeled shells.
- The persistent vertical rail independently toggles the black notebook/section pane and gray page pane. The black pane is one ordered tree: an expanded active notebook's sections render immediately beneath that notebook rather than after later notebook rows. The sole section-add button is in the black-pane header: it adds beneath a focused section or at notebook root after blank-space focus clearing. The black pane never renders pages, and the gray pane never renders child-section navigation nodes.
- `文件` opens a full-screen dark Backstage-style notebook/options surface. Prediction and direct-versus-recursive page display live in Options rather than the main workspace. The compact icon-only drawing ribbon uses physical-pixel hairline separators, opens a tool flyout only on the second click of an already selected tool, and leaves the canvas free of permanent page/tool/zoom overlays.
- The Windows Backstage left navigation is 160 px wide, with a matching 136 px return target, so all Chinese navigation labels remain fully visible at the supported minimum window width.
- The panorama hand is a pen-only tool: selected hand mode makes stylus contact pan instead of authoring, while finger and mouse behavior stays direct-content. Selecting it again opens its 25–300% sensitivity flyout, while choosing a drawing tool exits the mode; two-finger pan/pinch is always available and has priority. Middle-button navigation spring-loads the same visible Hand state and restores the prior tool on release. The stylus branch still needs physical confirmation.
- The navigation rail is 32 dp and its centered section/page controls show a compact selected frame while their pane is expanded. Normal canvas chrome exposes search plus enter-full-page; full-page mode replaces all normal chrome with a centered floating drawing toolbox and pen/search/exit controls. Only the full-page pen toggles that floating toolbox.
- The cursor/text tool is removed from both products, shortcut catalogs, and full-page toolbar catalogs. A blank finger/primary-mouse click creates a temporary caret without immediately requesting Android's keyboard; only the first valid character commits a page text box, while focus/operation loss discards an empty one without an undo record. Existing text uses ordinary Select -> Edit clicks. The sample library intentionally omits copied `更多笔记本`, `快速笔记`, and quick-notebook entries.
- Pressure-eraser stylus lift now unconditionally clears the visible eraser circle as it exits temporary erase mode.
- The `开始` ribbon provides working clipboard, rich-text character/paragraph formatting, styles, and pure-red page tags. Tags use a one-shot choose-then-place interaction and tag search recenters the document on a chosen result.
- Options and its quick-toolbar configuration page are full-screen. Any implemented Start or drawing command can be added to or removed from the horizontally scrollable full-page toolbar.
- Pane type headings are removed. The black-pane `+` offers notebook or section creation; notebook rows are focusable so a section can be added at notebook root, while a focused section receives the new child section.
- Tags can be selected by direct tap or containment lasso/marquee, transformed with the shared selection frame, and removed by the whole-stroke eraser. Stylus contact independently follows the selected pen tool and never inherits a finger/mouse direct-content state.
- Selecting a notebook in the black pane clears section focus and updates the gray pane: in direct/current-section mode the page list is empty and page addition is grayed out/disabled, while in recursive mode the gray pane displays all sections, nested sub-sections, and pages across the entire notebook.
- Section and page rows now separate two long-press outcomes: the context menu opens immediately at the long-press threshold without waiting for release, while movement after a shorter 180 ms arm delay starts manual ordering and release performs only the drop; earlier movement remains list scrolling. Menus provide rename/delete, same-notebook move/copy, links, section merge/export/new-child, and page cut/copy/paste; hierarchy/order persist, copied pages retain embedded assets, and deletion removes page-owned data after confirmation.
- User settings are persisted across app restarts and updates in `SharedPreferences`: tool presets (widths, pressure, flow, opacity, colors, eraser radius, added tools, selected tool), pressure-eraser dwell time (`dwellMillis`) and pressure threshold (`pressureThreshold`), sidebar visibility (`sectionPaneVisible`, `pagePaneVisible`), page pane mode (`CURRENT_SECTION` vs `RECURSIVE`), and active notebook/section/page selection.
- Pressure erasing is owned only by the designated real eraser preset on both products: its 0.25–40 mm radius, enable switch, 40–100% threshold, and 50–500 ms dwell are configured in that eraser flyout. Pen and highlighter can spring-load it with visible toolbar/cursor state; lasso and panorama hand cannot, and release restores the source tool.
- The working icon-only View ribbon now provides normal/full-page and independent split-view modes, 50/75/100/125/150/200/400/800% presets and step controls, a persistent lock that blocks only finger-pinch scaling while keeping two-finger translation and ribbon zoom actions active, 100% reset, page-width fit, the shared baseline menu, page colors, unbounded or Word-style continuously growing A4 paper, optional document-surface page titles, and light/dark page backgrounds. A4 grows from either content or downward viewport browsing, never shrinks automatically, and clips patterns to each sheet so the workspace gaps stay blank. Split slots keep independent navigation/page/view session state, sidebars follow the focused slot, and exit keeps the right slot. Page appearance and baseline settings persist per page; logical black ink and default canvas text remap with the background.
- The add-page dialog has shared page/PDF tabs. Normal pages use the same paper/color/baseline catalogs as the View ribbon and remember the last successful configuration. PDF import creates one real annotated page per source page at the original PDF point size, adds a muted gray-pane `PDF` suffix, retains original/rendered assets, and preserves source MediaBox dimensions in layered export.
- Finite page surfaces no longer mask overflow content; text, ink, tags, and insert objects remain visible and movable outside resized paper over a `#0A0A0A` workspace.
- Full-page mode uses a content-sized configurable floating toolbar and replaces the hidden black navigation rail with two standalone floating pane buttons.
- Insert commands use proper Fluent vectors with no visible labels; delayed hover labels are app-rendered as white text on `#3A3A3A`, avoiding the platform light tooltip.
- Two-finger transform input waits for platform-scaled pan/span slop, then locks the pointer sequence to pan or scale. The installed protected Notein APK exposes `NoteinZoomHandle` only as a reference, so exact Notein thresholds remain unavailable and are not claimed.
- v0.5.0's first gesture-arbiter build had a self-recursive transform-end helper and crashed with `StackOverflowError` on pause/pointer-up. The helper now explicitly clears the activity flag and cancels the arbiter, with cancel/restart behavior covered by a unit regression test.
- The Insert ribbon now implements editable tables, embedded attachments, PDF printouts rendered as one image per page, first-sheet spreadsheet previews with external Excel editing, batch image insertion, built-in/user sticker presets, and note-only links. Inserted objects and original assets persist per page in app-private storage.
- Android's canvas host observes the full pointer stream above text and object children, so a second finger cancels a pending direct action and starts page pan/zoom even when the first finger landed on content. Selection uses one light dotted frame with eight compact square handles and 48 dp nearest-handle touch targets. Move/resize waits for platform slop; a stationary hold opens the menu; unselected-content drag starts containment marquee instead of moving that object. Page pan/pinch preserves selection. Image quarter-turn rotation, flips, ordering, restore-size, and background state persist; PDF page-background objects remain immutable.
- Both Android and Windows have a persisted shortcut panel categorized by File/Start/Insert/Draw/View concrete actions. Android alone filters the exact Xiaomi device internally, but its product UI calls the device `触控笔` and shares the same full-screen options layout as `常规`. Android ribbon drop-downs plus section/page/object context menus use one app-owned dark icon command surface rather than OEM `PopupMenu`. Windows provides keyboard mappings plus middle-drag pan and Ctrl+middle zoom. The current Android suite passes 114/114 unit tests, debug/test APK assembly, and lint.
- `开始 -> 粘贴` and Ctrl+V now use the system image clipboard on both clients before falling back to text or the internal object clipboard. Windows accepts bitmap/file-drop image data and Android accepts foreground-readable image `ClipData` URIs; both copy the image into page-owned storage, preserve its aspect ratio, apply the configured image display mode, and persist the object.
- Windows' Page/PDF creation dialog is an opaque, device-pixel-rounded ClearType window rather than a layered transparent window. Full-page pane header add controls opt into the native caption hit-test region, and empty/whitespace temporary text has no visible selection frame.
- Windows right-button source classification tolerates Wacom drivers that mix raw barrel events with promoted mouse events. Mouse right-click still uses the system drag threshold, but the stylus side button must never use movement distance: release within the Windows `GetDoubleClickTime()` window is always a context click despite nib jitter. Nib contact shows the lasso preview immediately; an in-window release discards it without changing selection, while holding past the window makes the same path committable. In-air side-button clicks open the same context menu but cannot lasso without contact. Stylus menus wait for the promoted stream to drain; completed lassos never open a menu. A new barrel/right press while an app-owned menu is open closes it and rearms the timed gesture; middle down also closes the menu and resumes pan/Ctrl-zoom. Raw/mouse release, capture loss, focus/tool/document change share one cleanup path. Pen/highlighter contact inside an existing mixed-selection frame drags it; contact outside clears it and writes.
- Windows drawing-tool flyouts preserve the state seen at pointer-down: an unselected tool click only selects, a second click opens its flyout, and clicking any tool while a flyout is open closes the old flyout; a different tool is selected without immediately opening its menu.
- Image/PDF import policies apply only to dark pages and default to automatic inversion. The view filter follows `invert(89.4118%) hue-rotate(180deg)` calibrated to `#1b1b1b`; visual exports retain display state and source bytes remain unchanged.
- PDF export writes native ink/shape/glyph geometry on both clients with the current page appearance. Printing owns the independent conversion/selection/pattern options. Windows ordinary pages retain Optional Content Groups; source scenes preserve interleaving, and Android native PDF has no layer toggle. `.mjn` keeps editable page JSON, original assets, and intentional PNG layer previews. See `docs/design/insert-and-export.md`.
- Android canvas background patterns now render in a dedicated layer below inserted images/PDF printouts/stickers/tables/attachments, while dry and wet ink remain above inserted content. The fix passed unit tests, debug assembly, and lint; an installed tablet screenshot with two existing imported images verified that the grid remains visible around them without crossing their opaque pixels.
- Static native inspection proves a Xiaomi-only prediction route in this Notein build: its JNI facade loads Xiaomi's `/system_ext/lib64/libtflite_interface_wrap.touch.so` and the bundled `assets/predict/estimate.hdb` model. An isolated probe succeeded after declaring that public native library: availability `true`, model init `0`, and one raw inference returned two floats. Xiaomi's official Pencil Engine SDK is the legitimate product integration route; do not ship Notein's model.
- Design records:
  - `docs/design/ios-client.md` (read before Apple UIKit layout/input, Pencil gestures, Apple storage/exchange or distribution).
  - `docs/design/writing-performance-and-durability.md` (read before Windows wet/dry rendering,
    pen feedback, Undo, autosave/recovery, or same-page split ownership).
  - `docs/design/shapes-and-straightening.md` (read before shape geometry, hold-to-straighten, shape settings, or generated-stroke persistence).
  - `docs/design/boundless-canvas.md` (read before boundless page growth, paper extents, or smart-cropped PDF export).
  - `docs/design/cross-platform-parity.md` (read before any shared Windows/Android product feature or defect; shared behavior is updated, tested, and delivered on both clients by default).
  - `docs/design/sync-architecture.md` (read before synchronization or note-format work).
  - `docs/design/live-note-sync.md` (read before canonical shared-store, autosave, incoming
    refresh, Syncthing deployment or source-preserving round-trip work).
  - `docs/design/handwriting-engine.md` (read before ink-input, brush, rendering, or tablet-lab work).
  - `docs/design/stored-ink-appearance.md` (read before Android shared-stroke opacity/color or legacy pen loading).
  - `docs/design/input-gestures.md` (read before shortcut, gesture arbitration, viewport, or pressure-eraser work).
  - `docs/design/workspace-ui.md` (read before notebook/section/page navigation, tool configuration, or workspace styling).
  - `docs/design/insert-and-export.md` (read before insert objects/assets, image/PDF view filters, note links, or export formats).
  - `docs/design/export-and-print.md` (read before output scopes, physical pagination, export/print surfaces, or native print adapters).
  - `docs/design/windows-desktop.md` (read before Notein-win storage, navigation, canvas, desktop UI, import/export, or release work).
  - `docs/design/data-migration.md` (read before Notein/OneNote export, backup parsing, imported hierarchy, fallback, or migrated-PDF work).

# Active Work

- [x] Complete OMEN/phone/tablet canonical note storage, durable ordinary Android content,
  idle incoming refresh, stale-write rejection and disposable native round-trip acceptance.
  Deliver the unified version including the separately verified color/navigation fixes.
  Large-page duplicate JSON/string graphs and inactive-session accumulation are removed;
  cold loading of very large imported pages still has a visible wait.

- [x] Android old synced pen/color and two-finger/right-button repairs: legacy ordinary fountain
  pens display opaque without rewriting JSON; source outlines/shapes retain alpha. Two fingers
  preempt/cancel mouse and Xiaomi-key secondary gestures through last-finger lift, and independent
  button/hover streams cannot end navigation. The tablet native color probe passed all twelve RGB
  samples and ten outline-alpha cases; Android 161/161 tests, APK/test-APK and lint pass (0 errors,
  130 warnings). Native gesture test APK installation was rejected by HyperOS; physical gesture
  acceptance remains pending. On 2026-10-12 the user authorized coordination with the active sync
  chat, which owns final integrated commit/deployment to tablet and Redmi K70, including the
  SharedStrokeCodec opacity hook and InkColorProbeActivity. Read `docs/design/stored-ink-appearance.md`.

- [x] Integrate displayed-color export, dark-page printing, individual image inversion and hidden
  print backgrounds with the shared paginated-output UI; validate both clients and refresh the
  sole EXE/current APK. Windows is reopened against its existing workspace. Tablet installation
  remains pending the user's decision about its current session-only ink.

- [x] Implement/build the native iPhone/iPad client and independent Pencil gesture settings; verify core tests and initial native simulator interactions.
- [ ] Collect actual iPad/Pencil acceptance and configure the user's Apple development Team for device installation; continue the parity work tracked in `docs/design/ios-client.md`.

- [x] Add shared standard/narrow/wide two-column ruled backgrounds in the ribbon menus and new-page
  chooser. Implementation and shared `.mjn` names are in the root checkout; isolated Windows
  151/151 tests pass (paper-centered rendering, viewport/crop independence, A4 gaps, print hiding,
  saved/package round trips and actual menu/new-page chooser checks). Android 143/143
  tests, APK/lint/test-APK assembly passed in
  `C:\Users\Meta\AppData\Local\Temp\.agents\mojian-two-columns-20261008`.
  Commits `57f7bbe` and `dff4c43` are pushed to `origin/master`. The final unified EXE/APK
  includes these changes. See `docs/design/workspace-ui.md`.

- [x] Verify and deliver the shared small-image move / aspect-preserving corner-resize repair
  from an isolated build checkout; Windows reopened, Android installation pending a current ADB
  connection. The separate clipboard task's work remains in this checkout.

- [x] Re-export the 2026-10-08 Chemical Kinetics homework as a true white-paper/black-ink vector PDF; replace both clients' raster PDF routes, validate native paths/opacity/color with independent PDF readers, and refresh both deliverables while preserving notes.

- [x] Diagnose the residual zoomed-out Windows pen slowdown with actual rasterization, replace
  per-segment fountain drawing with filled vector geometry, separate ordinary wet ink, reject
  handoff-altering bitmap caches, verify 128 tests and refresh/reopen the sole EXE with identical
  saved stroke/point data. Historical recovery is no longer requested.

- [x] Repair Windows small-page/hover performance and save reliability, verify pixel parity and
  real-window close flush, normally replace/reopen the Release against the existing workspace,
  preserve the current handwritten page and verify single-process ownership. Physical pen
  acceptance and identification/recovery of the exact earlier missing page remain pending.

- [x] Expand both clients' zoom to 6400%, add the shared high-zoom presets and Android shortcut actions, verify clamped anchor-preserving gestures and viewport restore, exercise the real WPF menu/raster path, and refresh both deliverables. Windows reopened; tablet installation remains pending a current ADB connection.

- [x] Review the shared vector-arrow UI polish, regenerate all Windows icon resources, verify both clients, and refresh the sole Windows Release EXE and current Android APK. Windows release reopened; tablet installation remains outside this build request.

- [x] Add the shared 1 mm default pressure-eraser travel cutoff, eraser-only persistent slider and regressions for low-pressure travel, retraced paths, pending-timer cancellation, lift reset and free movement after activation.

- [x] Implement and persist the shared Shapes tool and configurable pen/highlighter hold-to-straighten; inspect current Notein resources without accessing live notes. Native Android seven-shape rendering/editing/storage codec checks and an isolated real WPF shape panel/canvas check pass. See the design record for the remaining physical-pen acceptance boundary.

- [x] Repair missing notebook management on Windows and Android: own row menu, persisted rename,
  confirmed whole-notebook deletion, split/session cleanup, empty-last-notebook replacement, and
  regression checks. Both deliverables refreshed; Windows menu verified on a real isolated window,
  current EXE reopened, tablet update/cold launch passed, and the five live Windows notebooks retained.

- [x] Rebuild and activate native Windows Notein/OneNote imports; preserve the old workspace/local notebooks/settings, validate every referenced asset, and prove real-canvas editing and original-PDF refresh. Android imported scene parity and exact Notein brush reconstruction remain outside this Windows source-import boundary.
- [x] Delete all imported Notein pages without their original PDF, including annotations, as requested; prune empty sections and validate 681 retained pages with zero broken asset references.

- [x] Implement Android/Windows boundless paper anchored at document origin, content-triggered right/down expansion with a 24 mm margin, monotonic persisted extents, and current-content PDF cropping with 6 mm padding. Windows 86/86 and Android 114/114 unit tests plus Android lint passed; current EXE/APK were refreshed and the tablet update cold-launched successfully.

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
- [x] Split rule/grid/dot rendering out of the dry-ink view so page patterns stay beneath imported images and other inserted content.
- [x] Add Android-only configurable Xiaomi single/double/hold shortcuts, cross-platform keyboard shortcut panels, stylus-aware panorama mode, direct inserted-object touch transforms, and immediate sidebar long-press menus.
- [x] Replace Android OEM drop-down/context menus with the shared dark icon command surface, align the shortcut page with general options, and repair inserted-image finger selection/transform routing.
- [x] Supersede the earlier cursor/double-tap/global-lasso experiments with the documented per-device input contract: direct touch/mouse Select -> Edit, temporary blank-click text, containment marquee, pen-only Ink/Lasso/Hand, reserved canvas double-click, and unconditional second-finger page navigation.
- [x] Initialize and port Notein-win Windows desktop frontend (.NET 10 WPF MVVM) with OneNote-style dark workspace, ribbons, recursive section navigation, dual-mode page pane, multi-layer canvas, and full xUnit test suite.
- [x] Replace the rejected Notein-win prototype with the persistent Windows desktop product defined in `docs/design/windows-desktop.md`, then publish and exercise its actual self-contained Release EXE.
- [x] Reconcile the Windows document interaction and Page/PDF creation paths with the latest Android model, then repeat isolated real-Release persistence and menu acceptance.
- [x] Capture and compare real Android vs Windows screenshots, align Windows UI 1:1 with Android baseline across PageCreationDialog card layout, sidebar section color strips/chevrons/page headers, Insert ribbon icon tinting, and Backstage Options cards.
- [x] Generate brand app icon for 墨笺 (Mojian) featuring obsidian squircle, notebook page, and glowing lavender fountain pen nib; apply to Windows PC (EXE icon/Assets) and Android tablet (adaptive/round mipmaps), publish Release build, install to tablet, and create desktop shortcut.
- [x] Repair Windows full-page enter/exit icon clipping and centering, native-caption hit testing, hidden-rail spacing, and transient sidebar visibility.
- [x] Align Windows Ctrl+pen/middle drag with fixed down-right/up-left diagonal zoom and add panorama-hand plus physical-pen cursor feedback.
- [x] Preserve physical-stylus hover coordinates while de-duplicating only Windows' mouse-compatible copy; keep the stylus itself as the driver for middle pan and Ctrl+middle/Hand zoom.
- [x] Make middle-button stylus navigation start from in-air movement immediately, with no second nib contact/click, while switching to the high-resolution stylus stream after contact.
- [x] Repair Windows pressure erasing so pressure may cross the threshold after stylus-down and dwell activation immediately erases at the stationary point without requiring another move.
- [x] Add temporary right-button freeform lasso for mouse drag and in-air stylus side-button drag while preserving stationary right-click menus on release.
- [x] Reserve Backspace/Delete for mixed selection deletion outside native text editing, remove deletion actions from tool flyouts, de-duplicate stylus/mouse navigation streams, remove the visible predicted leading segment, and publish the sole current Release EXE with a desktop test shortcut.
- [x] Make Windows page creation render sharply, keep full-page add actions clickable, hide empty-text frames, repair driver-promoted right-button lasso, and unify pressure eraser/panorama spring-loaded state, sensitivity, persistence, and eraser range across Windows and Android.
- [x] Close every Windows temporary right-lasso exit path so hover cannot extend a released gesture, and make drawing-tool flyout first-click/second-click/dismissal transitions deterministic.
- [x] Stop released barrel-button snapshots from rearming lasso on the next pen contact, and interleave the active notebook's section tree directly beneath its notebook row.
- [x] Port the canvas mouse right/middle contract to Android and give Xiaomi single-Lasso shortcuts the documented short-menu/long-lasso time-window behavior with immediate preview.
- [x] Remove all older Windows candidate directories and build intermediates; retain only the current Windows Release and copied current Android APK.
- [x] Synchronize Windows and Android system-image clipboard paste and Word-style viewport-driven A4 growth, with a durable two-client parity completion rule.
- [x] Remove dense-import redraw and viewport-save stalls on both clients with cached stroke bounds, viewport culling, compact page JSON, and real 407,230-point Windows render measurements.
- [x] Export and convert the user's Notein/OneNote libraries into a validated staging workspace with editable content first, separate visual fallbacks, retained vector PDF sources, and zoom-adaptive Windows PDF display.
- [x] Migrate and validate all OneNote and Notein notebooks into the persistent live Mojian workspace (`C:\Users\Meta\Documents\Mojian`, 5 notebooks, 1,445 pages, 544,641 editable strokes, 1,921 assets, 0 errors) with rollback copy retained in `_migration/pre-import-workspace`.
- [ ] Collect physical acceptance for direct Select -> Edit text, first-tap keyboard suppression, containment marquee over a large image, second-finger preemption, pen-only Ink/Lasso/Hand independence, and Insert Space finger/pen drag plus pen/mouse hover preview.
- [ ] Collect physical Windows acceptance for Backspace/Delete mixed deletion, outward Ctrl+pen-side-button zoom, smooth middle drag, and absence of a leading pen line.
- [ ] Collect physical feedback for stationary pressure erasing and recover its observable Notein threshold/dwell mapping.
- [ ] Collect the user's unplugged subjective feedback on the single adjustable stroke, especially nib alignment, taper, and pressure/ink-flow response.
- [ ] Prove a read-only Notein stroke renderer before any write-back experiment.

# Build / Run / Test

- Apple client: `bash ios/tools/build.sh test|simulator|device`; see `ios/README.md`. Full Xcode is required; the Swift package alone is not the UIKit app. The current simulator app is `ios/artifacts/current/Mojian-simulator.app`. Signed archives require `MOJIAN_APPLE_TEAM`. Xcode 27 on this Mac provides Device Hub at `/Applications/Xcode.app/Contents/Applications/DeviceHub.app`; iOS runtimes are separate Components downloads.
- Windows desktop client (`Notein-win`):
  - Build solution: `dotnet build mojian\Notein-win.slnx` or `cd mojian; dotnet build`.
  - Run unit tests: `cd mojian; dotnet test`.
  - Migration regression tests: `uv run --project tools/data-migration python -m unittest discover -s tools/data-migration/tests`.
  - Optional actual-source acceptance: set `MOJIAN_VECTOR_PAGE_JSON` to a rebuilt imported page JSON before `dotnet test`; it renders 1x/8x and verifies vector edits without saving changes to that source file. UI screenshot tests use an isolated workspace/preferences file.
  - Optional real pagination acceptance: set `MOJIAN_PAGINATION_PAGE_JSON` to a continuous A4 page JSON before `dotnet test`; the product planner generates independent vector sheets into temporary QA files without changing the source.
  - Launch desktop app: `cd mojian\src\Notein-win; dotnet run`.
  - Publish the delivered EXE: `dotnet publish mojian\src\Notein-win\Notein-win.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o mojian\artifacts\release`.
  - Release executable: `mojian\artifacts\release\Mojian.exe` (publish output is intentionally gitignored).
  - Before publishing, remove the prior `mojian\artifacts` output after confirming no delivered EXE is running. After tests/publish, run `dotnet clean mojian\Notein-win.slnx -c Release` and remove any remaining project-local `bin/obj`; keep exactly one current Release directory.
- ADB binary: `C:\Users\Meta\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- Verify device: `adb devices -l`.
- Installed package path: `adb shell pm path com.orion.notein`.
- APK inspection tools currently available: Android SDK `apkanalyzer`, Java 17, and Scoop-installed Rizin 0.9.1. `jadx` is not globally installed; use a temporary portable copy if needed.
- Keep device probes read-only until a tested backup/restore strategy exists. Do not enable Notein cloud sync or write into its live data directory during format discovery.
- Initial handwriting lab package must remain `com.meta.inklab`, request no network/storage permission, and store no durable user content.
- Lab build and lint: from `android`, run `.\gradlew.bat :app:assembleDebug :app:lintDebug`.
- Workspace unit tests: from `android`, run `.\gradlew.bat :app:testDebugUnitTest`; navigation tests cover unlimited section depth and both page-pane modes, while tool tests cover required defaults and add-only preset behavior.
- Shapes device probe, Debug only: launch `com.meta.inklab.debug/com.meta.inklab.ShapeProbeActivity` directly; read `cache/shape-probe.json` through `run-as`. It uses only an isolated native view and cache, never user pages/settings. It verifies seven shapes, codec point/ID/width recovery, move/scale/erase and Undo/Redo. Build instrumentation separately; do not use connected Gradle tests against the installed product.
- Before Android cleanup, copy the current APK to `android\artifacts\current\Mojian-debug.apk`; then run `.\gradlew.bat clean` so `android\app\build` is not retained between deliveries.
- View tests cover explicit anchor-preserving zoom through 6400%, pan/zoom-out at the upper bound, high-zoom snapshot/persistence recovery, real WPF 6400% menu/status and thin-vector rendering, page-width fitting, A4 content/viewport auto-growth, legacy paper migration, page-view preference JSON round trips, clipboard image MIME recognition, inserted-image aspect-ratio sizing, viewport culling, cached bounds, compact page persistence, and no whole-page autosave on viewport-only changes.
- Debug APK: `android\app\build\outputs\apk\debug\app-debug.apk`; installed debug package/activity: `com.meta.inklab.debug/com.meta.inklab.MainActivity`.
- Tablet install: direct `adb install -r -t -d` succeeds over wireless ADB and immediately launches via `am start`.

# Durable Lessons

- An Android parent that starts consuming a touch sequence in `dispatchTouchEvent` must hand the
  already-targeted children an `ACTION_CANCEL`; returning true alone leaves their timers (long
  press, GestureDetector) running and produces delayed menus after the gesture has ended.

- Syncthing (Go) and scanners open synced files without `FILE_SHARE_DELETE`, so Windows
  rename/replace of a note can transiently fail while the file is being hashed. Every write into the
  synced workspace must tolerate that lock; never let a storage exception escape a WPF input handler.
  Real-window tests can flake while the delivered EXE is running; rerun before treating as a regression.

- A .NET dictionary can reuse a removed entry's old slot. Coalesced autosave jobs must select by an
  explicit submission sequence; remove/reinsert does not guarantee current enqueue order and can
  otherwise regress shared library metadata after a newer page save.

- A successful import count does not prove native content: validate independent outline edits, source stacking/hit testing and zoomed raster output. Notein ColorLong's upper ARGB word and OneNote cache-only `InsertedFile` attachments were both missed by the older converter.
- WPF asynchronous PDF completion must dispatch cache mutation/invalidation onto the owning canvas dispatcher. An STA alone does not guarantee that an await resumes on that UI dispatcher; frozen bitmaps can cross threads, visual invalidation cannot. WPF tests also share one Application and must not race its creation.

- Notein metadata and document blobs are split across Android app-private and app-specific external storage. Syncthing of only `/Android/data/com.orion.notein/files` is not yet proven complete or safe.
- The installed China build differs from the global Play package (`com.orion.notein.global`); do not assume global-package paths or backup behavior apply.
- Launching Notein can immediately activate configured Huawei Drive/analytics components. Keep it force-stopped during static work and avoid launches that are not needed for a deliberate local-backup experiment.
- Notein 2.0.10.0 uses the exact AndroidX Ink 1.0.0 arm64 native library. Reproduce the public Ink input/rendering pipeline and clean-room brush behavior. Never instrument or replace the live installed Notein package; deeper unpacking, if needed, belongs in a separately signed isolated environment.
- A driver-mapped stylus button is a semantic input, not a guarantee that every promoted WPF event keeps its stylus association. Source classification must combine button state and recent hover, while coordinate de-duplication allows raw and compatibility streams to feed one gesture. Temporary eraser/Hand behavior must spring-load the actual shared tool state instead of duplicating canvas-only settings, or toolbar, cursor, persistence, and behavior will diverge.
- The application manifest points to Baidu Protect's `com.sagittarius.v6.StubApplication`; defined-only DEX inspection contains the shell but not Notein business classes. Decompiled resource constants and native/JNI paths are exact evidence, while the protected pressure state machine still needs runtime observation or an isolated unpacking environment.
- Xiaomi publishes a Pencil Engine SDK that accepts current and historical MotionEvent points, takes the display refresh rate, and returns predicted points. Prefer that supported/authenticated SDK for Xiaomi-specific production acceleration, with AndroidX `MotionEventPredictor` as the portable fallback.
- The current new-stroke population consistently uses `notein-fountain-v1`, `pressure_correct=2.0`, `speed_correct=1.0`, and epsilon `0.01`; keep these as versioned evidence, not as proof of the full private brush graph.
- Notein's displayed percentages map directly to persisted correction values by dividing by 100. Preserve that boundary mapping, but do not call the lab's downstream curve Notein's curve until the runtime brush graph is recovered.
- This tablet's input becomes unstable while charging. Test brush behavior unplugged and keep charger-induced digitizer noise separate from smoothing or prediction conclusions.
- The Xiaomi pen buttons are Bluetooth keyboard PageUp/PageDown events from the exact device name `Xiaomi Smart Pen Keyboard`; never globally intercept those keycodes from ordinary keyboards.
- Notein resources prove that hold-to-erase is default-enabled and the visible default sensitivity is 20%, but protected code hides how that value maps to normalized pressure plus its exact dwell/slop constants. Keep the lab threshold explicit and tunable rather than presenting 78% / 140 ms / 2 dp as recovered constants.
- Android 15 edge-to-edge enforcement can put a custom rail under system bars. Apply actual system-bar insets and verify on the real portrait tablet, not only with a successful build.
- WPF promotes stylus activity into mouse events. A time-limited suppression window is insufficient for long strokes or side-button drags: while stylus input owns the gesture, suppress the promoted mouse stream for the full gesture and use only the latest coalesced stylus point for viewport movement. Replaying a whole stylus batch causes pan/zoom jitter and apparent direction reversal.
- Do not suppress all effects of promoted stylus mouse moves: this Windows Ink device relies on them for continuous in-air coordinates. They must refresh hover/nib feedback while remaining forbidden from driving a duplicate content or viewport gesture.
- A predicted stroke extension expressed in document coordinates becomes visibly long at high zoom. Windows must not draw a separate extrapolated segment ahead of the screen-space nib dot.
- Build outputs are replaceable delivery state, not history. Remove prior candidates before publishing and clean project-local intermediates afterward; retain one current Windows EXE and one copied current Android APK, while preserving reusable dependency caches and user data.
- `ViewStrokeRenderer` captures the canvas transform when `drawWithStrokes` starts. Apply viewport
  transforms inside its drawing block; applying zoom before entry makes its screen-space
  antialiasing omit the zoom even though Canvas still scales the geometry, producing soft edges.
- Keep wireless-debugging state and connection-port discovery separate: this tablet normally leaves wireless debugging enabled, while the ADB port is dynamic. If the user supplies a current endpoint, use it directly for that session; otherwise check existing connections and retry mDNS discovery. Never hard-code the last port, and never infer that the tablet is offline or wireless debugging is disabled merely because mDNS returned no service.
- A custom dark view hierarchy still inherits the application base theme for system menus and dialogs. Keep the Android base theme dark as well, or `PopupMenu` surfaces will remain light.
- Keep section and page pane data types separate. The black pane recursively flattens only `SectionNode`, but notebook rows and the active notebook's section rows must share one ordered render stream; separate notebook/section `ItemsControl`s append the active tree after later notebooks and visually assign it to the wrong parent. The gray pane either lists direct `NotePage` items or a separate recursive stream of section headers plus owned pages. A deep-page selection must not silently change the black-pane root.
- Keep temporary text, selected text, and formal editing as independent states. Android child Views cannot retroactively hand an already-consumed pointer stream to a sibling, so the canvas host must observe the complete stream above text/object children. On the second finger, cancel the pending single-pointer operation and route the sequence directly to the shared page-transform arbiter; do not solve this with child-level gesture detectors or delayed double-tap recognition.
- Do not run Gradle `connectedDebugAndroidTest` against the only installed copy of this debug product. Android Gradle Plugin/UTP may uninstall the target before installing test APKs; if Xiaomi then rejects the install confirmation, both the app and its `allowBackup=false` private workspace disappear. Build the instrumentation APK separately and use an isolated package/device or an explicit backup before any connected test.
- Render and hit-test a mixed ink/tag/text/inserted-object selection through one top-level overlay, but keep the underlying content transforms in their owning layers so selection chrome never becomes document content. Direct object selection callbacks clear other layer selections, while a completed lasso must populate every layer before rendering the combined frame.
- Preferences load before the view hierarchy exists. Restore canvas-affecting values into ordinary state fields, then apply them while building the corresponding view; never dereference a `lateinit` canvas view from `loadPreferences()`.
- Treat A4 as a paginated document policy, not one fixed canvas rectangle. Grow the stored sheet count before either content or the viewport reaches the last sheet's bottom, clip paper patterns to each sheet, keep explicit inter-sheet gaps, and never shrink automatically while editing.
- Treat every defect or feature in a shared product concept as a Windows-and-Android task by default. Trace, update, test, document, and refresh both deliverables together; leave one client untouched only for a documented platform-specific boundary.
- Imported handwriting can exceed 400,000 points on one page. Interactive renderers must cache stroke bounds and cull against the inverse-mapped viewport; viewport-only motion must never rescan all points or synchronously serialize the complete page. Rebuild/invalidate caches on every stroke replacement or point transform, and bypass culling only for export.
- WPF `Path` defaults can clip Android 24x24 vector data when a control supplies only width/height. Set `Stretch=Uniform`, preserve per-path literal colors through `DrawingImage`, and regenerate icon resources from Android XML rather than hand-flattening geometry.
- A successful DWM `WM_NCHITTEST` result does not prove that caption buttons are visible: opaque
  WPF content over the glass frame hid the DWM buttons on Windows 11. Verify caption controls by
  actual pixels (app-drawn buttons are client content, so `PrintWindow` with a DPI-aware capturing
  process sees them). Snap Layouts require returning `HTMAXBUTTON` for the app-drawn maximize button.
- Keep semantic canvas layers in distinct top-level views: paper and rule/grid/dot patterns below inserted content, then dry/wet ink, text, and tags above it. Combining page patterns with dry ink forces the patterns above otherwise opaque images even when the pattern is drawn first inside that view.
