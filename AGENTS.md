# Goal

- Build 墨笺 (Mojian) as a persistent native Windows/Android note app with editable handwriting,
  one canonical folder format and safe local-first synchronization across Windows, Android and macOS.
- iOS/iPadOS is excluded from development, fixes, tests and releases by explicit user direction on
  2026-11-13. Existing ios/ is historical. macOS uses the independent shared/swift package.
- Preserve existing Notein/OneNote data. Notein research is clean-room behavioral interoperability;
  never publish proprietary application code, models, keys or private notes.

# Current State

- 2026-11-26 生命周期服务器0.3.1/core0.3.4已上线，真实DR恢复revision1676/1923项通过；普通升级不复制笔记。客户端0.7.45/code49支持祖先过期保留冲突，Windows37同步、Android348项(0失败/2跳过)、release/lint通过；已发版，部署与离线平板边界见当月日志。

- **macOS 0.2.4 receive-only sync**: user requested current notes while keeping Mac read-only on
  2026-11-26. GET-only HTTPS receiver, ordered pinned routes, streaming verified downloads, retained
  recovery, offline journal replay before model load and foreground refresh are implemented.
  `Documents/Mojian` uses separate `.mojian-sync/macos-readonly` state and private Application Support
  credentials; never upload/adopt this copy as writable or copy another device's checkpoint.
  Read `docs/design/macos-readonly-sync.md`. Shared core25 + desktop36 tests pass, including an
  actual isolated server and offline replay with a fresh receiver. Installed `/Applications/Mojian.app` and
  published `macos-v0.2.4` (ZIP929262B); production rev1676/1804 live files, settled changed0. See November log.
- **0.7.44 (Windows/Android code48)**: the two native clients isolate conflicted page/PDF dependency groups,
  retain reviewed choices across offline restart, and negotiate paged discovery and resumable transfers.
  v0.7.44 prerelease is published with uploaded EXE166187988B and APK17313968B; tag points to ff1630b.
  `deferred-incoming.json` retains consistent groups awaiting review. A partial or unverifiable
  publication keeps `incoming.json` and the startup edit lock. OMEN and ROG Windows executables and
  K70/Pad Android are upgraded. OMEN shipped EXE passed isolated two-way sync and preserved real
  checkpoint 1146/incoming 1348. K70 production LAN sync was observed in the app. Mac 0.2.3
  opened its copied library read-only. See November log and native-sync.md for the recovery boundary.
- **0.7.43 (Windows/Android code47)**: merged all outstanding Claude conflict-review
  and delayed page-loading branches, retaining branch ancestry and the current save barriers.
  Android/Windows conflict review has M/A/D navigation, per-file choices and red/green/yellow ink
  previews; page edits (including unified undo and appearance) are locked during review. Status is
  passive. Current test totals are under Active Work. K70/liuqin installed in place with original signer/prefs/time;
  cold launch359/350ms, processes stayed running. K70 dexopt calls a missing dex2oat32; app has no crash.
  Windows EXE deployed to OMEN repo and ROG Programs/Mojian with no running product at replacement.
  v0.7.43 prerelease published with EXE/APK; Mac/OMEN current outputs refreshed. Physical input acceptance stays open.
- User authorized Mojian deployment and future routine updates on 2026-11-25 without repeated
  approval. Preserve notes/settings/signers/sync. The 2026-11-26 request separately authorizes the Mac
  read-only sync update; retired iOS stays outside scope.

- **0.7.42 editor fixes**: one chronological per-page history covers all ink/objects/text/appearance,
  one entry per gesture; undo retains viewport and canonical assets/metadata. Zoom multiply/divide1.05;
  presets unchanged. See document-history.md; test and release receipts are in docs/log/2026-11.md.

- Android tile recovery since 0.7.41 checks RenderNode.hasDisplayList and window visibility;
  liuqin native lifecycle regression passed. Read android-page-navigation.md before rendering changes.
- liuqin PixelOS17 reports xdpi≈30.9 (real≈309); Vector module com.meta.liuqin.dpifix corrects
  Mojian/Notein thin ink. User confirmed case magnets caused pen dropout below application routing;
  authorized KPM liuqin-pen-charge-bypass is active, normal-boot autoload unverified. Keep palm/two-finger
  routing unchanged. Recovery task ~/.agents/liuqin--stylus-input/AGENTS.md; source ~/Developer/liuqin-pen-charge-bypass.
- Windows physical undo shortcut double-press timing is 150–800ms/default300; toolbar undo is
  immediate. Android keeps its existing button policy. Read workspace-ui.md before undo-input changes.
- Never deploy clients older than Windows/Android0.7.36/code41 or macOS0.2.3 against this library:
  pointsCoded is already in use. Stored outline size caused Android OOM; retain compact outlines,
  streaming reads and original unknown fields. Read ink-storage-format.md/handwriting-engine.md.
- Android product is non-debuggable Release with legacy package com.meta.inklab.debug and OMEN's
  original signer. Never uninstall/clear data; the suffix does not imply a debug product.
- Navigation/settings are device-local; canonical schemas, fixtures and fountain constants live in
  shared/. Physical Windows pen/stability, Android hold-width and native PDF output acceptance remain
  open. Details of 0.7.20–0.7.41 behavior, fixes and deployment receipts are in docs/log/2026-11.md.
- Independent sync-server **0.2.0** pins `@alexbeatsz/file-sync-core@0.2.0`; its `sync-server-v*`
  tags/releases never replace product latest. ROG `MojianNativeSync` runs `Programs/MojianSyncServer`
  using `%LOCALAPPDATA%/MojianServices/.local/native-sync/server.json` and its data. Preserve config/data,
  gateway module paths, and service state. ROG is live on 0.2.0; loopback and direct LAN/Tailnet
  TLS passed 1,923 items, pages, delta and Range. The Sakura gateway still serves v1 manifest and
  Range, while pages require direct TLS. Its live startup wrapper is missing from disk; retain
  the process until the gateway restart path is verified. See `sync-server/README.md` and `native-sync.md`.
- **2026-11-23 native-only libraries**: OMEN, K70 and liuqin retain only `Documents/Mojian`;
  former Syncthing libraries were removed, Mojian-native renamed with preferences/private credentials.
  Native identities/checkpoints/journals/conflicts remain; K70/liuqin then matched revision1348/1923 entries.
  OMEN retains an existing conflict and incoming journal; see native-sync.md. Mac already uses
  `Documents/Mojian` as a read-only copied library, without native HTTPS support. ROG is backend-only.
  Never register these native libraries with Syncthing. Routes are
  tried in strict configured order; private certificate pins do not alter global trust. Preserve
  `.local/native-sync/connection.json` and never publish it or its access values.
- ROG sync uses loopback 18742 and HTTPS 18743, composed Dayweave gateway 18781; subscription 18782,
  Dayweave 49732. Do not restart SakuraFrpService or overwrite the original gateway module.
- User selected restored/tablet659 and declined 90 old editable pages and the phone's divergent
  version. Do not reintroduce them or generated `page-notein-summary-*` PDF backup pages (removed
  with the native deletion transaction). Keep actual annotated PDF backgrounds and original assets.
- Windows selected root is `C:\Users\Meta\Documents\Mojian`; Android clients select
  `/storage/emulated/0/Documents/Mojian`. Missing remembered libraries must be refused,
  never silently recreated. Old migration-path recovery and historical counts are in monthly logs.
- Windows deletion recovery lives in `.mojian-deletions.previous`; corrupt JSON copies live outside
  `notebooks/` in `.mojian-corrupt.previous`. Incoming partial sync locks editing until journal
  replay AND repository reload finish. Unmergeable conflicts require per-file manual review; 0.7.43 adds the UI.
- Global theme/new-page background/print choices are device-local (`settings-memory.md`). Imported
  Notein/OneNote presentation is display-only; preserve source coordinates, outlines and assets.
- Native macOS history: chat `01a18ccf-8616-7ff1-aa48-9654cd0930ae`; Mac receive-only sync/update
  was requested on 2026-11-26. Feather branding remains in shared/branding.
- Current output paths: `mojian/artifacts/release/Mojian.exe`,
  `android/artifacts/current/Mojian.apk`. Historical iOS artifacts are not maintained.
  Historical version/deployment details have moved to `docs/log/2026-10.md` and `2026-11.md`.

# Active Work

- 0.7.45/code49 同步兼容有限历史：旧合并祖先404保留冲突并继续其他页面；本次构建使用提交源码隔离归档，不携带 Material UI 工作树。预览 Android code49 日后发布前需要递增。

- **Material 3 Expressive experiment**: branch `codex/material-3-expressive`; user requested
  preview before further builds on 2026-11-26. Interactive proposal is
  `docs/previews/mojian-material-expressive.html` (desktop/tablet/phone, dark/light, pen settings).
  Native Android/Windows edits are committed on this branch only (2026-11-27, unaccepted); do not
  merge/release/deploy them before design review. Initial Android compilation passed but lint reported 14 errors
  (13 existing indentation sites exposed by the new dependency, plus PassiveStatusView
  AppCompat inheritance); final native checks remain pending. Windows task-specific tests
  were stopped at the user's request. Read `docs/design/material-expressive.md`.

- 0.7.28 PDF import/inversion awaits physical device acceptance; Android native fixture is compile-only.
- Windows pen/stability and Android stability/hold-width acceptance remain open; 0.7.20 tablet feel was accepted.
- Windows physical writing/erasing acceptance remains pending. Current SSH full suite: 328/329,
  existing Hand-pan pixel failure mean 1.870 / shifted 4.546. Preserve the original threshold.
  Windows native sync 33 scenarios and sync integration 11/11 pass. Android: 341 passed/1 opt-in
  skipped, APK/test APK/lint pass. Shared Swift 23/23 and macOS 20/20 pass.
- Android folder-picker/permission/save-recovery/export/print, physical eraser-after-contact
  and direct-selection acceptance remain separate. Native instrumentation is compile-only.
- Sync prevention: 0.7.40 merges JSON conflicts automatically; unmergeable conflicts and their dependencies await
  per-file review. Android/Windows review UI is included in 0.7.43. Recovery evidence is in native-sync.md/November log.
- Not done from the review: splitting MainActivity/NoteCanvas/MainViewModel further, a WPF-free core
  assembly, unified sync state layout, recovery/conflict directory retention policy.

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

- A cached Android `RenderNode` can outlive its display list after HWUI resource/tree cleanup.
  Check `hasDisplayList()` before reuse (including prefetch); map membership alone can retain blank
  ink until a zoom/edit. Window visibility and native display-list eviction need rendering regressions.
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

- `docs/design/material-expressive.md`: experimental Google Material 3 Expressive proposal and review boundary.

- `docs/design/document-history.md`: page edit history, transaction boundaries, metadata/assets, fine zoom.
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
- `docs/design/macos-readonly-sync.md`: GET-only Mac receiver, configuration, recovery and foreground lifecycle.
- `docs/design/ios-client.md`: historical only (iOS retired 2026-11-13).
- `docs/log/2026-10.md`: completed work, validation and historical entry-state archive.
- `docs/log/2026-11.md`: November completed work.

- 服务器数据生命周期：`docs/design/server-data-lifecycle.md`；正式数据、历史、DR 与升级回滚独立，普通升级不复制数据。新服务器版本的部署结果以当月日志为准。
