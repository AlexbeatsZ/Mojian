# Goal

- Build 墨笺 (Mojian) as a persistent native Windows/Android note application with editable
  handwriting, one canonical folder format, and safe local-first synchronization.
- Bring the native iOS/iPadOS client to feature parity, including configurable Pencil gestures.
- Preserve existing Notein/OneNote data. Reverse engineering is clean-room interoperability with
  the user's notes; do not publish proprietary code, models or keys.

# Current State

- Current source/build version: Windows and Android **0.7.2**; Android retains
  `com.meta.inklab.debug`, version code **10**. iOS remains a separate 0.1.0 client.
  `v0.7.2` prerelease is published with EXE/APK assets uploaded; source is pushed to `origin/master`.
  The 0.7.1 sync/folder task is committed independently; this review integrates on top without
  changing its deployed backend, gateway, credentials or real notes.
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
- The live Syncthing setup and real notes were not changed by this delivery. The established
  Windows library is `C:\Users\Meta\Documents\Mojian\_migration\native-20261002\workspace`;
  phone/tablet shared storage is `/storage/emulated/0/Documents/Mojian`.
  Five notebooks / 653 pages were the last deployment acceptance count, not a current inventory.
- Latest task-only 0.7.1 evidence (2026-10-28): Android **232/232** unit tests including an opt-in
  real public HTTPS round trip; debug/test APK builds, lint **0 errors / 137 warnings**. Native sync:
  **11/11 Node integration tests**, **2/2 gateway tests**, and **24/24 Windows scenarios** on both
  loopback and actual Sakura HTTPS. Windows focused safety/default-config checks **15/15** and
  Release self-contained publish succeed. Production was restored to revision 0/files 0 after
  disposable public tests. Physical Android acceptance remains outstanding.
- Full Windows Debug and Release suites now pass **191/191** including caret and screenshots.
  Tests share a persistent STA/Application/Dispatcher rather than reusing a dead owner; failures
  close leaked windows and restore resources; assembly disposal shuts down Application and joins
  the STA so testhost exits and releases DLLs. The caret fixture clears default sample content
  before its blank click and asserts that a temporary caret was actually established.
  Earlier failures and the older pan-cache pixel baseline remain historical log evidence.
- Current deliverables: `mojian/artifacts/release/Mojian.exe` and
  `android/artifacts/current/Mojian-debug.apk`. Builds are not installation or physical acceptance;
  this round does not start the real app, install an APK or migrate notes. The separate ROG
  backend/shared-gateway deployment is authorized and complete.
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
- Combined Android 0.7.2: **239 passed / 1 opt-in public-HTTPS test skipped / 0 failed**;
  debug/test APK compilation and lint **0 errors / 137 warnings**. Popup tracking is weak, clears
  its host anchor and dismisses on Activity destruction instead of statically retaining Context.

# Active Work

- Complete physical Android folder-picker/permission/save/restart and native foreground-sync
  acceptance before live-library adoption. The ROG HTTPS backend is deployed; real Syncthing
  libraries remain unchanged and must not be pointed at native sync in place.
- Native sync v1 conflicts require reviewed manual recovery; there is no automatic resolution UI,
  CRDT merge, end-to-end encryption or blob/tombstone/receipt garbage collection.
- Physical Android save/recovery/foreground-sync and export/print acceptance remains outstanding.
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
  round's process/recursive-build cleanup was policy-blocked; no retry through another shell or
  forced product closure was attempted. Final fixed Release/Debug runs exited with no new hosts.
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

- `docs/design/native-sync.md`: dedicated clones, foreground lifecycle, recovery/deployment boundaries.
- `docs/design/android-folder-selection.md`: explicit system picker, new library and legacy-private entry.
- `sync-server/PROTOCOL.md`: exact shared wire/CAS/idempotency and mutation-epoch contract.
- `docs/design/android-page-navigation.md`: save barriers, asynchronous binding and viewport memory.
- `docs/design/live-note-sync.md`: deployed canonical storage and Syncthing acceptance.
- `docs/design/export-and-print.md`: scope, pagination, bounded preparation and output limitations.
- `docs/design/insert-and-export.md`: assets, clipboard, vector output and image policy.
- `docs/design/page-backgrounds.md`: shared background geometry and original desktop reference.
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
