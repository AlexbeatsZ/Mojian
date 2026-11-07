# Goal

- Build 墨笺 (Mojian) as a persistent native Windows/Android note app with editable handwriting,
  one canonical folder format and safe local-first synchronization; bring iOS/iPadOS to parity.
- Preserve existing Notein/OneNote data. Notein research is clean-room behavioral interoperability;
  never publish proprietary application code, models, keys or private notes.

# Current State

- **0.7.25 published (Windows/Android code31, source `d940f41`)**: pen color row has a configuration button plus a separate
  saved-preset selector. New/edit named styles own pressure/speed curves, direction, tapers, stability,
  tilt and turn response. Configured new ink snapshots `pen-style-v1` and its canonical outline;
  historical readers stay unchanged. Read `pen-style-presets.md`; task `~/.agents/mojian--pen-styles`.
- **Windows 0.7.26**: dry scene keeps a permanent `BitmapCache` (render thread was saturated
  re-tessellating old ink under wet ink); topmost new ink on layered pages uses the wet overlay;
  v2/pen-style eraser hits use modeled samples (`StrokeEraserHitTest`, 8.5 ms -> µs per candidate).
  Includes 0.7.24 same-canvas focus fix. Physical pen acceptance open; see perf design doc 0.7.26.
- **Windows 0.7.23** (`v0.7.23`, source `8c9aa58`): retained per-item WPF visuals, stable paper/image/PDF nodes, indexed
  delta erasing (no page JSON on contact) and layered wet-ink updates. Synthetic 3,000 strokes /
  240,000 points: eraser contact 353 -> 2.2 ms, allocations 110.7 -> 0.33 MB; 60 moves cause zero
  full scene recordings. Final Release suite 256 passed + one clipboard-restore failure, which
  passed an isolated retry (257 covered). Real selected-page read-only rendering also passed.
  Self-contained EXE is published on GitHub and replaced at the sole local release path while no
  product was running (0.7.23+8c9aa58); next launch uses it. Physical PC pen feel is open.
  Read `writing-performance-and-durability.md`; continuation `~/.agents/mojian--windows-ink-performance`.
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
- Delivery/build status for this active task: `~/.agents/mojian--handwriting-parity/AGENTS.md`.
  Tablet now has Android 0.7.22/code30, preserving the 0.7.21 handwriting changes.
  Windows 0.7.21 source tests pass except the pre-existing Hand-pan pixel baseline described below;
  Windows 0.7.21 EXE is published and replaced on OMEN while no product process was running;
  `v0.7.21` prerelease includes EXE+APK. Phone is still 0.7.14; ROG client was not updated.
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
  replay AND repository reload finish. Conflicts require manual review; no automatic merge UI.
- Global theme/new-page background/print choices are device-local (`settings-memory.md`). Imported
  Notein/OneNote presentation is display-only; preserve source coordinates, outlines and assets.
- iOS is 0.1.1/build2; signing needs an Apple Team and physical Pencil acceptance remains open.
  Native macOS development is separate chat `01a18ccf-8616-7ff1-aa48-9654cd0930ae`; no Mac deployment
  is authorized by the earlier Windows/Android deliveries. Feather branding remains in shared/branding.
- Current output paths: `mojian/artifacts/release/Mojian.exe`,
  `android/artifacts/current/Mojian-debug.apk`, `ios/artifacts/current`.
  Historical version/deployment details have moved to `docs/log/2026-10.md` and `2026-11.md`.

# Active Work

- 0.7.25 EXE+APK are uploaded; Windows 267 tests and Android 288 tests pass (2 opt-in skipped).
  Local APK is current. Windows `v0.7.26` EXE (b788430) is published and supersedes the staged 0.7.25 EXE;
  local replacement waits for running 0.7.24 PID39720 to exit normally. No Android install.
- Tablet 0.7.21 startup/stability UI is verified; physical feel remains separate.
- Physical Windows pen feel/stability and Android stability/hold-width acceptance remain open.
  Existing 0.7.20 tablet feel was accepted; this is not a new 0.7.21 physical acceptance.
- Windows 0.7.26 install (after the running product exits) and physical writing/erasing acceptance are pending. Hand-pan pixel checks pass with their
  original thresholds; the 0.7.21 SSH-only failure remains historical evidence in the November log.
- Android folder-picker/permission/save-recovery/export/print, physical eraser-after-contact
  and direct-selection acceptance remain separate. Native instrumentation is compile-only.
- Native sync v1 manual conflicts, no E2EE, and no blob/tombstone/receipt GC remain as designed.
- iOS parity gaps and physical iPad/Pencil acceptance: `docs/design/ios-client.md`.

# Build / Run / Test

- Windows: `dotnet test mojian/Notein-win.slnx -c Release` on OMEN. Use isolated task output with
  `--artifacts-path` if DLLs are locked; successful tests can leave testhosts, so verify before cleanup.
  Do not kill unrelated processes. The common WPF executor owns one live Application/Dispatcher.
- Publish: `dotnet publish mojian/src/Notein-win/Notein-win.csproj -c Release -r win-x64
  --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
  -o mojian/artifacts/release`. Check no real Mojian process runs before replacement; never force-close.
  Keep one current output and clean project-local bin/obj after delivery; retain dependency caches.
- Android (Java17/SDK36/Gradle8.13), from `android`:
  `gradlew --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintDebug`. Copy APK to `artifacts/current` before `gradlew clean`.
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
- Apple: `bash ios/tools/build.sh test|simulator|device`; signed device output needs `MOJIAN_APPLE_TEAM`.

# Durable Lessons

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
- Pen/mouse promoted streams require ownership-aware deduplication. Xiaomi pen keys are exact-device
  PageUp/PageDown; HyperOS intercepts long presses, so essential functions cannot depend on them.
  Evaluate physical pen noise unplugged; don't confuse charger effects with prediction quality.
- Preserve imported outlines/unknown fields and original source PDFs. Import counts or successful
  compilation do not establish editability, interoperability, native output or physical latency.

# Docs Index

- `docs/design/pen-style-presets.md`: independent selector/configuration, named style editing and immutable stroke snapshots.
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
- `docs/design/ios-client.md`: native UIKit/Pencil and parity gaps.
- `docs/log/2026-10.md`: completed work, validation and historical entry-state archive.
- `docs/log/2026-11.md`: November completed work.
