# Goal

- Build 墨笺 (Mojian) as a persistent native Windows/Android note app with editable handwriting,
  one canonical folder format and safe local-first synchronization across Windows, Android and macOS.
- iOS/iPadOS is excluded from development, fixes, tests and releases by explicit user direction on
  2026-11-13. Existing ios/ is historical. macOS uses the independent shared/swift package.
- Preserve existing Notein/OneNote data. Notein research is clean-room behavioral interoperability;
  never publish proprietary application code, models, keys or private notes.

# Current State

- **0.7.40 (Windows/Android code44, source only)**: native sync three-way merges JSON pages/library per
  key and per id (`NativeSyncMerge`, shared `json-merge.json`); strokes added on two devices both
  survive, volatile-only resaves receive the server page. Not released or installed. Read native-sync.md.
  Android conflict review UI (navigation 同步冲突 group, M/A/D colors, review bar, red/green/yellow
  canvas preview) on branch `claude/epic-solomon-61a32e`; emulator screenshots only, not installed.
- **Android 0.7.39/code43**: pen-style curves compile to AndroidX `Linear` via `curveMapping` so the
  response is exactly 1 at x=1 (Ink's implicit end point). 0.7.26–0.7.37 snapped the Notein speed curve
  back to full width at >=64 cm/s (blunt fast tails). Notein preset values never changed. Windows unaffected.
  Release v0.7.39 (APK); OMEN/Mac current APK replaced; tablet updated in place 2026-11-22 (phone not).
- **Windows 0.7.38**: `双击撤销重做` targets the graphics-tablet undo key: two presses of the undo
  shortcut (default Ctrl+Z) within the adjustable `双击判定时间` (150–800 ms, default 300) redo;
  Windows undo buttons are immediate again. Android stays 0.7.37/code42 (button variant unchanged).
  Read workspace-ui.md before changing undo input. Release v0.7.38 (EXE); OMEN artifacts EXE replaced.
  Physical tablet acceptance open.
- **0.7.37 (Windows/Android code42)**: Windows pen tap/press circle feedback removed (`PenFeedback`).
  Windows tool flyout re-anchors after toolbar rebuilds, fixing the jump when switching pen style.
  Tablet/phone not installed; physical acceptance open.
- **0.7.34 (Windows/Android code39, branch `fix/compact-ink-outline`)**: tablet "页面暂时无法读取"
  was Android OOM on a 358 MB page (作业 4); 90% was Windows pen `vectorPath` written since 0.7.21 as
  one full-precision disc per modeled sample. Windows now writes simplified, 0.01 DIP, relative
  outlines (wet == stored geometry); Android streams page JSON (`StreamingJson`). Existing pages are
  re-encoded by `tools/outline-compaction` only after confirming every device has synced them
  unchanged (`--skip` others). Win 312/312, Android JVM 310 (2 opt-in skipped), Release/lint pass.
  Deployment/compaction status is in `docs/log/2026-11.md`. Read handwriting-engine.md "Outline size".
- **0.7.36 (Windows/Android code41), macOS 0.2.3**: strokes may store `pointsCoded` instead of
  `points` (`shared/ink-points.md`); all readers since these versions accept both, so never deploy
  an older client against the migrated library. Library packed on 2026-11-18 (3150 -> 1774 MB).
  Rewrite library files only after the all-device hash/base check in `ink-storage-format.md`.
- **Android 0.7.35/code40**: `StreamingJson` shares repeated names/short values; 作业 4 (70 MB) now
  opens on the tablet at 177 MB Java heap. Points as JSON objects remain the main memory cost;
  Notein/OneNote research and the packed-points proposal are in `ink-storage-format.md`.
- **Android 0.7.33/code38** fixes whole-workspace focus highlighting after Xiaomi pen keys on
  liuqin PixelOS 17. StylusShortcutLayout keeps pre-IME focus routing with default focus highlight
  disabled. Release installed in place on the tablet, user confirmed physical pen presses retain
  normal colors. JVM 305 pass/2 skipped, Release/native-test APK/lint passed.
- **Android 0.7.32/code37**: deploy the signed Release variant (`Mojian.apk`), with version name
  `0.7.32` and `debuggable=false`. Retain legacy package `com.meta.inklab.debug` and OMEN's original
  signer for in-place updates preserving notes/settings/sync. The package suffix is compatibility,
  not a build mode. User explicitly requires release installations on phone and tablet.
- **2026-11-16 brand 0.7.31 (Windows/Android code36), macOS 0.2.1**: installed display name is now English
  `Mojian` (Pixel 2026 white-tile icons); package `com.meta.inklab(.debug)`, macOS bundle ID, data roots
  and signers unchanged. Installed in place: Mac `/Applications/Mojian.app` 0.2.1, OMEN and ROG
  `mojian/artifacts/release/Mojian.exe` 0.7.31 (no running process), K70 code36 (first install time kept).
  No Release and no UI/sync acceptance by user direction. Tablet offline, not updated.
- **0.7.30 published (Windows/Android code35, source `e37604f`)**: architecture repair from review
  `mac:claude:efdd324c`. Device-local navigation (selection/expansion/active notebook) moved out of
  library.json into Windows settings.json / Android prefs; autosave no longer rewrites the index.
  Shared contracts in `shared/`: workspace/page schemas, golden fixtures, sync decision table,
  `fountain-v2.json` constants (consumed by both engines). 409 `version_conflict` drops the outbox and
  re-decides next pass; remote library.json deletion is refused. Missing page files are errors, not
  empty pages; copy section/page publishes only after data copy; Windows unchanged strokes are written
  back verbatim (`StrokeJsonConverter`); undo keeps frozen per-stroke versions, bitmap caches are LRU
  budgeted, PDF import stages previews on disk. Content IDs follow the sync path rule on all clients
  (ASCII `[A-Za-z0-9._-]`, no leading/trailing dot). macOS client + `shared/swift` core now on master.
  Temporary Windows eraser switches update selection without rebuilding tool collections/pen controls.
  v0.7.30 prerelease has EXE/APK uploaded; current artifacts refreshed on Mac and OMEN.
  Release closeout reused completed tests/build/signature receipts; physical device acceptance is open.
- **0.7.29 published (Windows/Android code34, source `b0a020f`)**: built-in pen configurations are now `Notein风格` + `Onenote风格`
  (id `onenote`), from shared/pen-style-presets.json. OneNote was measured on OMEN's desktop OneNote 16 via
  synthetic pen injection + COM ISF readback: ellipse tip, raw points, width×(0.25+1.5p), no speed/taper.
  Example docs/examples/pen-configurations/onenote.json must equal the preset. Not installed on any device:
  the tablet could not connect; deploy only when the user asks. Win 284/284, Android 298 pass/2 skipped.
- **0.7.28 published (Windows/Android code33)**: PDF page-pane import creates one note per file,
  with vertically stacked immutable backgrounds and one original PDF asset. Both clients use the
  PDF original/invert/ask policy; physical output retains every source sheet and its size.
  Old split imports stay unchanged. Current EXE/APK refreshed; no device install/restart this task.
  Read `insert-and-export.md` / `export-and-print.md`; task `~/.agents/mojian--pdf-document-import`.
- **0.7.27 published (Windows/Android code32, source `c433980`)**: only `Notein风格` is preinstalled;
  original pressure/flow/stability stay visible. Configuration is JSON text/file import, with functions,
  engine parameters and configurable front controls. Selecting a file only fills the text box.
  WPF preset switching is fixed; historical ink/snapshots and 0.7.26 performance are preserved.
  Local Windows EXE replaced while closed; K70/Pad updated in place and optimized, Pad resumed normally.
  Read `pen-style-presets.md`; task `~/.agents/mojian--pen-styles`.
- **Windows 0.7.26**: dry scene keeps a permanent `BitmapCache` (render thread was saturated
  re-tessellating old ink under wet ink); topmost new ink on layered pages uses the wet overlay;
  v2/pen-style eraser hits use modeled samples (`StrokeEraserHitTest`, 8.5 ms -> µs per candidate).
  Includes 0.7.24 same-canvas focus fix. Physical pen acceptance open; see perf design doc 0.7.26.
- **Android 0.7.22** (code30): adds a focused root `dispatchKeyEventPreIme` entry for the exact
  Xiaomi pen keyboard, ahead of IME hardware-key processing; Activity remains the fallback.
  Existing bindings/state machine are preserved; `MojianPenKey` logs receipt and binding types.
  OMEN-signed and installed in place on the tablet, speed dexopt passed and first-install time kept.
  User confirmed buttons recovered and attributed the failure to Bluetooth not being updated.
  Actual logs now show upper single toggling lasso and lower single Undo/double Redo via Activity.
  This does not establish pre-IME handling as the cause of recovery. Capture has ended.
- **0.7.21**: Windows now authors `fountain-v2` with calibrated pressure/speed/direction,
  per-pen stability, distributed WPF packet timestamps, physical speed and canonical filled outlines.
  Android adds stability 0–100% (default 50; 1/10/20/60/100 ms at 0/25/50/75/100%) and persists it
  in tools/strokes. Both clients' hold-to-straighten uses `shape-default.WidthMm`, retaining source
  color/highlighter opacity. Old v1 ink stays unchanged. Read `handwriting-engine.md` and
  `shapes-and-straightening.md`. Windows queued snapshots cache frozen unchanged point lists.
- **Android 0.7.20** (code28): product build is non-debuggable despite the `.debug` package suffix.
  ART interpretation and scene-revision cache resets caused the earlier lag. User accepted the new
  tablet feel. Prior injected ~1900-stroke acceptance: frame p90/95/99 24/28/38 ms (Notein 24/27/44),
  dense save 0.33–0.9 s. `fountain-v2` was calibrated on device. These are prior 0.7.20 results.
- Android 0.7.19 caches unchanged stroke JSON/fingerprints; save checks use size + inode rather than
  HyperOS FUSE mtime. Idle save is 2 s, continuous dirty maximum 20 s; onPause wait is at most 1.5 s.
  Device-local append journal is not implemented. Read `android-page-navigation.md`.
- Independent sync-server **0.1.0** pins `@alexbeatsz/file-sync-core@0.1.0`; its `sync-server-v*`
  tags/releases never replace product latest. ROG `MojianNativeSync` runs `Programs/MojianSyncServer`
  using the original checkout `.local/native-sync/server.json` and data. Preserve config/data,
  gateway module paths, and service state. See `sync-server/README.md` and `native-sync.md`.
- Native sync is opt-in on dedicated `Documents/Mojian-native` clones on OMEN, tablet and phone;
  Mojian's Syncthing folder stays paused. Never enable native sync on a Syncthing tree. Routes are
  tried in strict configured order; private certificate pins do not alter global trust. Preserve
  `.local/native-sync/connection.json` and never publish it or its access values.
- **2026-11-08 OMEN sync recovery**: reviewed revision 288 conflicts and preserved 145 local added
  practice strokes plus 5 disjoint tablet erasures. Existing client now matches server revision 666,
  with no pending outbox/incoming/conflict. Homework5 matches the tablet/server's 2697 strokes.
  Full local/remote/resolved recovery copies remain under the existing conflict's resolution-20261108.
- ROG sync uses loopback 18742 and HTTPS 18743, composed Dayweave gateway 18781; subscription 18782,
  Dayweave 49732. Do not restart SakuraFrpService or overwrite the original gateway module.
- User selected restored/tablet659 and declined 90 old editable pages and the phone's divergent
  version. Do not reintroduce them or generated `page-notein-summary-*` PDF backup pages (removed
  with the native deletion transaction). Keep actual annotated PDF backgrounds and original assets.
- Windows selected root is `C:\Users\Meta\Documents\Mojian-native`; Android clients select
  `/storage/emulated/0/Documents/Mojian-native`. Missing remembered libraries must be refused,
  never silently recreated. Old migration-path recovery and historical counts are in monthly logs.
- Windows deletion recovery lives in `.mojian-deletions.previous`; corrupt JSON copies live outside
  `notebooks/` in `.mojian-corrupt.previous`. Incoming partial sync locks editing until journal
  replay AND repository reload finish. Unmergeable conflicts require manual review; no review UI yet.
- Global theme/new-page background/print choices are device-local (`settings-memory.md`). Imported
  Notein/OneNote presentation is display-only; preserve source coordinates, outlines and assets.
- Native macOS history: chat `01a18ccf-8616-7ff1-aa48-9654cd0930ae`; Mac deployment needs its own
  request. Feather branding remains in shared/branding.
- Current output paths: `mojian/artifacts/release/Mojian.exe`,
  `android/artifacts/current/Mojian.apk`. Historical iOS artifacts are not maintained.
  Historical version/deployment details have moved to `docs/log/2026-10.md` and `2026-11.md`.

# Active Work

- 0.7.28 PDF import/inversion awaits physical device acceptance; Android native fixture is compile-only.
- Physical Windows pen feel/stability and Android stability/hold-width acceptance remain open.
  Existing 0.7.20 tablet feel was accepted; this is not a new 0.7.21 physical acceptance.
- Windows physical writing/erasing acceptance remains pending. Current SSH full suite: 305/306,
  existing Hand-pan pixel failure mean 1.870 / shifted 4.546. Preserve the original threshold.
  Windows native sync 29 scenarios and sync integration 11/11 pass. Android: 306 passed/1 opt-in
  skipped, APK/test APK/lint pass. Shared Swift 23/23 and macOS 20/20 pass.
- Android folder-picker/permission/save-recovery/export/print, physical eraser-after-contact
  and direct-selection acceptance remain separate. Native instrumentation is compile-only.
- Native sync v1 manual conflicts, no E2EE, and no blob/tombstone/receipt GC remain as designed.
- Sync prevention: 0.7.40 merges JSON conflicts automatically; unmergeable conflicts still block the
  whole library and have no review UI yet (Shale's file tree/editor review is the reference design). Recovery evidence is in native-sync.md/November log.
- Not done from the review: splitting MainActivity/NoteCanvas/MainViewModel further, a WPF-free core
  assembly, unified sync state layout, recovery/conflict directory retention policy.
- iOS/iPadOS maintenance is retired by user direction, 2026-11-13.

# Build / Run / Test

- Windows: `dotnet test mojian/Notein-win.slnx -c Release` on OMEN. Use isolated task output with
  `--artifacts-path` if DLLs are locked; successful tests can leave testhosts, so verify before cleanup.
  Do not kill unrelated processes. The common WPF executor owns one live Application/Dispatcher.
- Publish: `dotnet publish mojian/src/Notein-win/Notein-win.csproj -c Release -r win-x64
  --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
  -o mojian/artifacts/release`. Check no real Mojian process runs before replacement; never force-close.
  Keep one current output and clean project-local bin/obj after delivery; retain dependency caches.
- Android (Java17/SDK36/Gradle8.13), from `android`: product packaging uses
  `gradlew --no-daemon --console=plain :app:assembleRelease :app:lintRelease`.
  Copy `app/build/outputs/apk/release/app-release.apk` to `artifacts/current/Mojian.apk` before clean.
  Use `:app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug` for contract checks only;
  do not install a debug artifact as the user's product. Check merged manifest `debuggable=false`,
  version and signer before deployment. CI's generated debug keystore is not OMEN's product signer.
  On Mac set JDK17/ANDROID_HOME and `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<real nonsymlink path>`;
  run `npm ci` in sync-server first for `NativeSyncWireContractTest`.
- Preserve `com.meta.inklab.debug` and OMEN's `~/.android/debug.keystore` signer. Mac builds need
  re-signing before `adb install -r -t`; never uninstall/clear data to fix signing. Product is not
  debuggable, so `run-as` is unavailable. ADB discovery/ports must be checked freshly.
- Never run `connectedDebugAndroidTest` or install a test APK on the only installed product: UTP
  can uninstall it and `allowBackup=false` would lose private notes. Native APK compilation is not
  device acceptance. Injected pen tests use temporary pages and must keep every sample in canvas;
  see `~/.agents/knowledge/android-device-ops.md`.
- Sync server: `npm ci && npm test && npm run test:integration && npm run package:server` from
  sync-server (Node22+). The .NET harness tests loopback HTTP, not public TLS or production notes.
- Migration: `uv run --project tools/data-migration python -m unittest discover -s tools/data-migration/tests`.
- macOS/Swift core: `bash macos/tools/build.sh test` (shared/swift + macos); `build.sh` packages the app.
- CI `.github/workflows/clients.yml` runs Windows contract tests, Android full JVM/lint and macOS tests.

# Durable Lessons

- AndroidX `EasingFunction.Linear` always returns its implicit (1,1) at x=1 and CLAMP pins every
  out-of-range source there. Any compiled response must equal 1 at x=1; check equivalence over and
  beyond the source range against google/ink's Linear semantics, not only inside it.
- Stored-format size is a correctness property: a per-sample outline that "only" costs 45 KB/stroke
  made dense pages exceed Android's 512 MB heap. Check bytes per stroke on a real dense page whenever
  an outline/serialization changes, and never parse whole pages via `readText()` on Android.
- Before rewriting library files on one device, compare each file's hash with every other device and
  its `.mojian-sync/state.json` base; a device with sync paused may hold unsynced edits.

- WPF cost hides on its render thread: measure per-thread CPU in a real window at the user's stored
  viewport, never only UI-thread timings at 100%. RDP/disconnected sessions invalidate timings.
  Windowed WPF testhosts can survive as unkillable one-core spinners; check before measuring.
- Frozen DrawingGroups in one page-sized Visual still make small edits dirty a whole scene.
  Retain per-content Visuals and unchanged paper/image nodes; measure contact allocations and
  rendered frames as well as geometry-recording cost. Undo history must release gesture indexes.
- Never ship the Android product debuggable: ART then disables nterp/AOT and uses generic JNI, which
  made Ink rendering and scene composition several times slower on the tablet than Notein.
- A host "scene revision" that is bumped for every edit must not be part of a render cache key that
  is meant to survive ink edits; profile on device (`simpleperf --app`, `gfxinfo framestats`) because
  unit tests cannot see these integration paths.
- Never wait unboundedly for the page write queue on the UI thread in lifecycle callbacks: dense
  pages take seconds per save and an unmerged queue backlogs during continuous writing (bg ANR kill).
- Page/sync change detection assumes every writer publishes by temp file + rename (new inode).
  An in-place, same-length rewrite of a canonical page by another program would not be detected.
- Do not re-check mtime around hashing either: on HyperOS it can change mid-hash with no write.
  Use size + inode for "file replaced while reading"; Mojian publishes by rename.
- Android shared storage mtime is not a change detector: on HyperOS 3.0 a renamed-over file
  first reports the new mtime, then minutes later the replaced file's old one (reproduced with
  plain `mv`). Use size/mtime only as a hint and confirm with content hashes before refusing writes.
- Directory names such as `_migration` do not imply disposable backups: check app preferences and
  sync configuration references before cleanup or writable startup against a remembered path.
- Shared product concepts require Windows/Android trace, tests and refreshed outputs together;
  native input/render adapters should remain platform-specific. Read the relevant design first.
- A save attempt is not a successful save. Preserve dirty state, propagate failure and block
  destructive follow-up operations. Incoming partial application must lock editing/saving until
  offline replay **and repository reload** both complete. Never clear a journal to silence errors.
- Outgoing sync snapshots need a mutation epoch spanning multi-file save/deletion publication.
  Per-file hashes or an incoming-only UI gate cannot prevent old-library/new-tombstone batches.
  Never hold a lock across large hashing, network awaits or UI dispatch; invalidate/retry instead.
- Syncthing's Windows handles can transiently deny rename/replace. Retry atomic writes; retain
  `.previous` recovery and conflict copies. Its local ignore rules do not propagate to other devices.
- Dense imported pages exceed 400k points. Cache bounds/cull by viewport, never serialize on
  viewport-only motion, and release the saved outgoing Android graph before parsing another page.
  A WeakHashMap may retain dead-key values until another access; actively drain a reference queue.
- Coalesced saves need explicit submission sequence; dictionary remove/reinsert is not queue order.
  Preserve the library snapshot when retrying a failed page+library job.
- WPF STA threads do not imply an active owning Dispatcher. Dispatch/freeze async PDF assets;
  tests must not reuse an Application whose STA has exited. Measure raster work at low zoom,
  not only command-recording cost; bitmap caches can change wet/dry edge pixels.
- Android parent gesture takeover must send child ACTION_CANCEL, including long-press timers.
  Nested PopupWindows must anchor to the host window, not a row inside a dismissed root popup.
- Keep mixed-selection chrome above semantic content layers. Page patterns stay below inserted
  images/PDFs; their rules must not paint over opaque images. A4 is a monotonic multi-sheet policy.
- Focusable Android routing containers need defaultFocusHighlightEnabled=false when they have no
  visual focus state. On PixelOS17, a hardware key otherwise highlights the full root after leaving
  touch mode. Compare focus/touch mode with screenshots before attributing washed colors to display HAL.
- Pen/mouse promoted streams require ownership-aware deduplication. Xiaomi pen keys are exact-device
  PageUp/PageDown; HyperOS intercepts long presses, so essential functions cannot depend on them.
  Evaluate physical pen noise unplugged; don't confuse charger effects with prediction quality.
- Preserve imported outlines/unknown fields and original source PDFs. Import counts or successful
  compilation do not establish editability, interoperability, native output or physical latency.

# Docs Index

- `docs/design/ink-storage-format.md`: stroke/point/outline storage size, Notein (AndroidX Ink) and OneNote (ISF) encodings.
- `docs/design/pen-style-presets.md`: JSON import, functions, controls, snapshots, built-in Notein/OneNote evidence.
- `docs/design/app-branding.md`: supplied artwork, platform icon packaging and regeneration.
- `docs/design/imported-page-presentation.md`: negative-coordinate legacy paper and coordinated image/ink contrast.

- `docs/design/native-sync.md`: dedicated clones, foreground lifecycle, recovery/deployment boundaries.
- `docs/design/android-folder-selection.md`: explicit system picker, new library and legacy-private entry.
- `sync-server/PROTOCOL.md`: exact shared wire/CAS/idempotency and mutation-epoch contract.
- `docs/design/android-page-navigation.md`: save barriers, asynchronous binding and viewport memory.
- `docs/design/live-note-sync.md`: deployed canonical storage and Syncthing acceptance.
- `docs/design/export-and-print.md`: scope, pagination, bounded preparation and output limitations.
- `docs/design/insert-and-export.md`: assets, clipboard, vector output and image policy.
- `docs/design/page-backgrounds.md`: shared background geometry and original desktop reference.
- `docs/design/settings-memory.md`: global theme, default background, last print choices and migration.
- `docs/design/windows-desktop.md`: native desktop storage/navigation/chrome and release contract.
- `docs/design/writing-performance-and-durability.md`: WPF rendering, save queue and recovery.
- `docs/design/cross-platform-parity.md`: two-client completion gate.
- `docs/design/workspace-ui.md`: notebook/section/page lifecycle, tools and settings.
- `docs/design/input-gestures.md`: per-device input, selection and pressure erasing.
- `docs/design/boundless-canvas.md`: monotonic growth and content crop.
- `docs/design/stored-ink-appearance.md`: source colors, alpha and legacy ink.
- `docs/design/shapes-and-straightening.md`: editable primitives and hold recognition.
- `docs/design/handwriting-engine.md`: clean-room pressure ink and reference evidence.
- `docs/design/data-migration.md`: Notein/OneNote backup conversion and native imported assets.
- `docs/design/sync-architecture.md`: original interoperability/storage investigation.
- `docs/design/macos-client.md`: AppKit client, canonical store, local-only write boundary.
- `docs/design/ios-client.md`: historical only (iOS retired 2026-11-13).
- `docs/log/2026-10.md`: completed work, validation and historical entry-state archive.
- `docs/log/2026-11.md`: November completed work.
