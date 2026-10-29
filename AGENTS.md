# Goal

- Build 墨笺 (Mojian) as a persistent native Windows/Android note application with editable
  handwriting, one canonical folder format, and safe local-first synchronization.
- Bring the native iOS/iPadOS client to feature parity, including configurable Pencil gestures.
- Preserve existing Notein/OneNote data. Reverse engineering is clean-room interoperability with
  the user's notes; do not publish proprietary code, models or keys.

# Current State

- Current source/build version: Windows and Android **0.7.6**; Android retains
  `com.meta.inklab.debug`, version code **14**. iOS remains a separate 0.1.0 client.
  Windows is updated in place to 0.7.6 and reopened on `Documents/Mojian-native`; Android devices
  remain installed at 0.7.5. Do not confuse a built APK with device deployment.
  The 0.7.1 sync/folder task is committed independently; this review integrates on top without
  changing its deployed backend, gateway, credentials or real notes.
- Device-local preferences now own one global light/dark mode, an independent new-page default
  background, and the last reusable print choices. Background menus link directly to the default
  row in options; single-page/one-off creation backgrounds do not change that default. Read
  `docs/design/settings-memory.md` before changing these paths. Theme overrides use display copies;
  Windows paper-growth events copy dimensions back to stored metadata, not the display theme.
- Old imported Notein/OneNote pages now use display-only continuous paper covering negative
  coordinates, default gray paper, and coordinated source ink/text/screenshot adaptation.
  Dominant screenshot backgrounds are recolored rather than blindly RGB-inverted, preserving
  black formulas and white text on colored backgrounds. Original coordinates, JSON and assets
  are retained; fixed PDFs stay fixed. Read `docs/design/imported-page-presentation.md` first.
  Sync overlays now have zero right/bottom outer margin on both clients.
- Sync status is now a compact bottom-right overlay on both clients. A4 grows only for content;
  pan/zoom does not publish changes. Windows keeps viewport in local settings and preserves old
  page viewport JSON; clean navigation/close does not increment page revisions or rewrite files.
  Unprefixed shared outlines render with nonzero winding, fixing transparent intersections;
  explicit F0/F1 remains authoritative. Read stored-ink-appearance.md before fill-rule changes.
- Android now explicitly opens a local folder using the system picker and remembers the choice.
  Unconfigured installs do not automatically select Documents/Mojian or private storage. An empty
  folder requires confirmation to create a canonical library; legacy private notes remain available.
  Read `docs/design/android-folder-selection.md` before changing these entry/lifecycle paths.
- The 2026-10-28 review follow-up integrates explicit Android save-failure barriers,
  source-first copying, reference-aware post-commit deletion cleanup, spacing-only rich-text
  persistence, shared scene ordering/revision caches and bounded logical-page export preparation.
  Windows deletion stages files in `.mojian-deletions.previous`, rolls back a refused index commit,
  and restores missing referenced files on load without overwriting existing content.
- Native HTTPS synchronization is **opt-in on explicitly created dedicated clones** on Windows
  and Android. V1 includes CAS, stable-ID durable outbox retries, tombstones, retained conflicts,
  offline incoming-journal recovery, foreground scheduling and mutation-epoch snapshot guards.
  Read `docs/design/native-sync.md` and `sync-server/PROTOCOL.md` before sync edits.
  Never enable native sync on a Syncthing tree. Both clients prefill
  `https://cdn.u3146344.nyat.app:63573/mojian-sync/v1/`; no token is shipped or sync auto-enabled.
- User-authorized ROG deployment adds startup task `MojianNativeSync` (loopback 18742) and wraps
  the existing `DayweaveHttpsGateway` on 18781. Subscription publisher stays on 18782; Dayweave
  stays on 49732. Do not restart SakuraFrpService or overwrite the original gateway module.
  ROG private config/data/recovery XML: checkout `.local/native-sync/`; local ignored
  `.local/native-sync/connection.json` contains connection details and must never enter Git/releases.
  Public Windows 24-scenario verification used a disposable backend, not production notes.
- Native sync settings now accept one address per line and try them strictly in input order on
  every pass. Routes share the stable endpoint/checkpoint and replay uncertain operations with
  the same ID. ROG optional HTTPS listener 18743 shares the same backend/data as loopback18742
  and Sakura. App-private exact certificate pins trust its self-signed SAN certificate; no global
  trust or proxy configuration is changed. Read native-sync.md before altering fallback behavior.
- Windows now opens `C:\Users\Meta\Documents\Mojian-native`, the selected659-page native clone.
  The former `_migration\native-20261002\workspace` was deleted in agy's 10:33 cleanup even though
  app preferences and Syncthing still referenced it. The new startup sample created at that missing
  path during deployment was withdrawn. User-authorized recovery subsequently restored the active
  canonical tree to that former path: 5 notebooks / 659 indexed pages / 1262 referenced assets,
  validator has no errors; its library and all 664 page files match the surviving tablet. This does
  not restore all deleted source/migration backups. Both Android clients now select
  `/storage/emulated/0/Documents/Mojian-native`.
  In-place installation preserved data. Daily sync uses ordered HTTPS routes; Mojian's Syncthing
  folder is now paused. The selected659-page seed was revision1/files1818. Latest live state is
  revision15,1818 manifest records/1803 live files/651 indexed pages; all three checkpoints match.
- Latest Windows Release suite passes **212/212**, including imported-page native pixels,
  original-data preservation, zero-margin status placement, local-only viewport/clean close,
  crossing alpha/PNG/SVG/independent PDF reader, menu/settings and print
  restoration after window restart; the previous 0.7.2 Debug suite passed 191/191.
  Tests share a persistent STA/Application/Dispatcher rather than reusing a dead owner; failures
  close leaked windows and restore resources; assembly disposal requests Application shutdown and
  joins the STA. The caret fixture clears default sample content
  before its blank click and asserts that a temporary caret was actually established.
  Earlier failures and the older pan-cache pixel baseline remain historical log evidence.
- Current deliverables: `mojian/artifacts/release/Mojian.exe` and `android/artifacts/current/Mojian-debug.apk`.
  Windows is 0.7.6; Android APK is 0.7.6/code14, not yet installed. The following live-device
  sync/input evidence is the previously recorded 0.7.5 baseline, not a new 0.7.6 acceptance:
  OMEN and phone actual UI report synchronized at the bottom-right.
  Tablet installed APK client catches up to revision15; lock screen prevents foreground UI
  acceptance. Phone strict1/2/3 public fallback passed; Windows previously passed first/second/third.
  Three checkpoints equal production, no pending outbox/incoming/conflict. Native pixel probes
  on phone/tablet pass12 palette samples,10 opacity cases and3 intersection fill-rule cases each.
  Phone actual50% zoom held20s changed0/656 page JSON, no library or manifest change; restored100%.
- Android and Windows use the original desktop background reference before `0033213`:
  spacing 24/32/48, uniform #2D3238, no red margin/major rules, dot radius 1 and A4 gap 32.
  `shared/page-backgrounds.json` is the executable contract. The 2026-10-19 tablet acceptance
  covered 59.6/60.2 MB page switching and 400% view retention, not physical erasing.
- Earlier implementation and device evidence has moved to `docs/log/2026-10.md` under the
  dated history archive. Do not treat historical counts, ports or test results as current state.
- The Zed review continuation fixes old save-retry library rollback, reentrant UI eligibility,
  overlapping export leases/repeated print layout, missing referenced copy assets and weak
  document-identity text baselines with one-pass preservation indexes. Android exports now bind,
  measure and draw offscreen layers on an exclusive Looper worker. PDF/print records one physical
  sheet at a time, closes native records immediately and assembles streams in disk-backed PDFBox;
  unused alternate print/whole-canvas PDF routes are removed. Ink/text/assets cull off-sheet work.
  A 96 MiB PDF stream regression passes in a 64 MiB JVM heap; native output remains compile-only.
- Android popup tracking is weak and clears/dismisses host anchors on Activity destruction.
- 0.7.5: Android247passed/1opt-in skipped, APK/test APK/lint0errors/144warnings; Node11/11
  and Windows native25/25 rerun. Both Android in-place installs retain preferences and library bytes.
- 0.7.6: Android251passed/1opt-in skipped, APK/test APK compiled, lint0errors/145warnings.
  A real 59 MB imported page was rendered read-only with gray paper and coordinated images/ink;
  Android native bitmap/text tests are compile-only, not installed or executed.

# Active Work

- Android installation and real-device acceptance for 0.7.6 have not been requested/performed.

- User selected restored/tablet659 and declined90 old editable pages and the phone's different
  version. Do not reintroduce these. Dedicated `Documents/Mojian-native` copies share the current
  manifest on all three devices. Syncthing stays paused. Continuation: `~/.agents/mojian--settings-memory`.
- Dedicated native copies are adopted. Physical Android folder-picker/permission/save-failure
  and tablet unlocked foreground acceptance remain separate; original Syncthing trees stay intact.
- Native sync v1 conflicts require reviewed manual recovery; there is no automatic resolution UI,
  CRDT merge, end-to-end encryption or blob/tombstone/receipt garbage collection.
- Physical Android save/recovery and export/print acceptance remains outstanding.
  Instrumentation tests (including spacing/export) are compiled, **not installed or executed**.
- Android export retains at most one decoded logical page and one native physical sheet; output
  streams use private cache storage, while page/resource metadata grows with output size. Do not
  claim whole-library atomic export, physical OEM acceptance or measured pen-latency gains.
- Collect physical eraser-after-contact/pen-key/direct-selection/input acceptance. Navigation
  continuation evidence: `C:\Users\Meta\.agents\mojian--android-navigation\AGENTS.md`.
- iOS still needs an Apple development Team, physical iPad/Pencil acceptance and the parity work
  listed in `docs/design/ios-client.md`. No signed IPA/physical Pencil result is claimed.
- A clean-room read-only Notein renderer/brush parity and subjective unplugged pen feel remain
  separate research/acceptance work. Historical note recovery was declined on 2026-10-08.

# Build / Run / Test

- Windows: `dotnet build mojian/Notein-win.slnx`; `dotnet test mojian/Notein-win.slnx`.
  UI tests use isolated workspaces and the shared WPF executor, not per-test STA threads.
  Pre-fix orphan testhosts (last observed 37056/36176/59300) may still lock old outputs. This
  historical process/recursive-build cleanup was policy-blocked; no forced product closure was
  attempted. In 0.7.3, a successful run also left testhost 51728 in the task's temporary build path;
  do not infer process exit from the test command's exit code. Use `--artifacts-path` in a fresh
  task-local temporary directory when output DLLs are locked, and preserve unrelated processes.
- Focused Windows safety checks:
  `dotnet test mojian/tests/Notein-win.Tests/Notein-win.Tests.csproj -c Release --filter "FullyQualifiedName~WorkspaceDeletionTransactionTests|FullyQualifiedName~WorkspaceUiMutationGateTests|FullyQualifiedName~DeleteNotebook_RemovesNestedPagesAndAssetsAndSurvivesRestart|FullyQualifiedName~DeleteInactiveNotebook_PreservesFocusedPageAndOtherSplitSlot"`.
- Publish: `dotnet publish mojian/src/Notein-win/Notein-win.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o mojian/artifacts/release`.
  Check the real app is not running before replacing its sole current release; do not force-close it.
  After validation/delivery, clean project-local `bin/obj`; keep one current release, not candidates.
- Android (existing Java 17 / SDK 36 / Gradle 8.13), from `android`:
  `.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`.
  Copy `app/build/outputs/apk/debug/app-debug.apk` to `artifacts/current/Mojian-debug.apk`
  **before** `.\gradlew.bat clean`. Retain reusable dependency caches.
- Never run `connectedDebugAndroidTest` against the only installed product. UTP can uninstall it,
  then HyperOS may reject reinstall; `allowBackup=false` would make its private notes unrecoverable.
  Do not uninstall, clear data or install a test APK on the live app for validation.
- ADB: `C:\Users\Meta\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  Check `adb devices -l` and mDNS; use a newly supplied endpoint immediately for that session.
  Empty discovery does not prove the tablet offline. Do not reuse old wireless ports as constants.
- Native sync: from `sync-server`, `node test-integration.mjs` builds a temporary package-free
  .NET harness and tests real loopback HTTP; `node --test test/server.test.mjs` runs the backend
  alone and skips the Windows scenario without its harness. These do not validate public TLS.
- Migration: `uv run --project tools/data-migration python -m unittest discover -s tools/data-migration/tests`.
  Optional read-only source checks use `MOJIAN_VECTOR_PAGE_JSON` / `MOJIAN_PAGINATION_PAGE_JSON`.
- Apple: `bash ios/tools/build.sh test|simulator|device`; see `ios/README.md`. Full Xcode required;
  signing requires `MOJIAN_APPLE_TEAM`. Current simulator output is under `ios/artifacts/current`.

# Durable Lessons

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
