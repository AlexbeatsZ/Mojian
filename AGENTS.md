# Goal

- Assess whether the Android Notein app on the user's tablet can be brought to Windows with PC-side writing and file-based synchronization through Syncthing instead of Notein cloud sync.
- Prioritize a clean-room handwriting engine that reproduces Notein's pen character and low apparent latency, because both the compatibility route and an independent product need it.
- Preserve the user's existing Notein data. Reverse engineering is for interoperability with the user's own notes; do not publish proprietary app code or keys.

# Current State

- Repository was initialized with feasibility commit `817787a`; it has no remote.
- Tablet is reachable through wireless ADB at `192.168.31.243:41931` (Xiaomi `23046RP50C`, Android 15 / API 35, arm64). The wireless-debugging port changes across sessions; rediscover it with `adb mdns services` instead of assuming the old endpoint.
- Installed China package: `com.orion.notein`, `v2.0.10.0` (`versionCode=343`, `minSdk=26`, `targetSdk=35`), single 114,190,617-byte APK, installed from Xiaomi Market.
- APK is non-debuggable and app-private `/data/user/0/com.orion.notein` cannot be read with `run-as`.
- App-specific external files are readable at `/sdcard/Android/data/com.orion.notein/files`; current snapshot is about 256 MiB / 5,638 files, mainly UUID-named blobs and PNGs.
- Static scan shows a native Android/Kotlin/Java app, not Flutter/React Native. It includes arm64-only native libraries such as `libink.so`, PDFium, OpenCV, SQLiteX, and Baidu Protect. Xiaomi CloudKit database/file sync providers are bundled.
- Runtime resources and logs show that editable data depends on a private `NoteContentDatabaseProvider`; the readable external directory is predominantly PNG previews.
- A user-authorized full local backup was created through Android's save picker as `/sdcard/Download/backup.zip` (1,579,602,577 bytes). Keep the tablet copy; the analysis copy is temporary and must not be committed.
- The backup is an unencrypted ZIP containing 167 per-note `.in` nested ZIPs, two `.template` entries, and one `.tree`; per-note containers include a Room/SQLite v25 database and metadata, plus source PDFs when applicable.
- Notein uses AndroidX Ink 1.0.0 exactly: its arm64 `libink.so` matches the Google Maven artifact in size and ELF Build ID.
- Android workspace prototype v0.2.0 is built as `com.meta.inklab.debug`. The earlier v0.1.0 lab was installed; v0.2.0 has not been installed because the tablet is not currently discoverable over ADB. It has no Internet/storage permission and remains separate from the untouched Notein package.
- The installed lab uses AndroidX Ink wet/dry authoring, motion prediction, one adjustable fountain-pen stroke, prediction toggle, undo, and clear. Controls are width 0.1–2.0 mm, pressure 0–400%, and ink flow 0–200%, with Notein-observed defaults 0.4 mm / 200% / 100%.
- The current source adds default two-finger pan/pinch zoom, a stationary-dwell pressure whole-stroke eraser, and an unbound Xiaomi shortcut interface. Completed ink applies the viewport inside `ViewStrokeRenderer` so screen-space antialiasing remains sharp while zoomed. Tests, build, and lint pass; the corrected APK was installed and cold-launched in 503 ms. Device capture proves the pen exposes `NVTCapacitivePen` plus `Xiaomi Smart Pen Keyboard`; its two shortcut keys send PageUp/PageDown.
- Workspace v0.2.0 adds OneNote-desktop-inspired dark chrome, recursively nestable sections, separate section/page panes with `本层` and `递归` page modes, per-tool flyouts, add/delete tool presets, logical black/blue/red pens, a translucent highlighter, explicit whole-stroke eraser, and functional lasso selection/delete. Search, non-drawing ribbon tabs, pixel erasing, and lasso move/resize are labeled shells.
- Static native inspection proves a Xiaomi-only prediction route in this Notein build: its JNI facade loads Xiaomi's `/system_ext/lib64/libtflite_interface_wrap.touch.so` and the bundled `assets/predict/estimate.hdb` model. An isolated probe succeeded after declaring that public native library: availability `true`, model init `0`, and one raw inference returned two floats. Xiaomi's official Pencil Engine SDK is the legitimate product integration route; do not ship Notein's model.
- Design records:
  - `docs/design/sync-architecture.md` (read before synchronization or note-format work).
  - `docs/design/handwriting-engine.md` (read before ink-input, brush, rendering, or tablet-lab work).
  - `docs/design/input-gestures.md` (read before shortcut, gesture arbitration, viewport, or pressure-eraser work).
  - `docs/design/workspace-ui.md` (read before notebook/section/page navigation, tool configuration, or workspace styling).

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
- [ ] Install and visually inspect v0.2.0 on the tablet after wireless ADB is re-enabled and rediscovered.
- [ ] Collect physical feedback for stationary pressure erasing and recover its observable Notein threshold/dwell mapping.
- [ ] Collect the user's unplugged subjective feedback on the single adjustable stroke, especially nib alignment, taper, and pressure/ink-flow response.
- [ ] Prove a read-only Notein stroke renderer before any write-back experiment.

# Build / Run / Test

- ADB binary: `C:\Users\Meta\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- Verify device: `adb devices -l`.
- Installed package path: `adb shell pm path com.orion.notein`.
- APK inspection tools currently available: Android SDK `apkanalyzer`, Java 17, and Scoop-installed Rizin 0.9.1. `jadx` is not globally installed; use a temporary portable copy if needed.
- Keep device probes read-only until a tested backup/restore strategy exists. Do not enable Notein cloud sync or write into its live data directory during format discovery.
- Initial handwriting lab package must remain `com.meta.inklab`, request no network/storage permission, and store no durable user content.
- Lab build and lint: from `android`, run `.\gradlew.bat :app:assembleDebug :app:lintDebug`.
- Workspace unit tests: from `android`, run `.\gradlew.bat :app:testDebugUnitTest`; navigation tests cover unlimited section depth and both page-pane modes, while tool tests cover required presets and add/delete behavior.
- Debug APK: `android\app\build\outputs\apk\debug\app-debug.apk`; installed debug package/activity: `com.meta.inklab.debug/com.meta.inklab.MainActivity`.
- HyperOS rejects streamed ADB installs with `INSTALL_FAILED_USER_RESTRICTED`. Use Xiaomi File Manager and the installer menu's one-time `单次安装授权`; do not enable a persistent global install setting.

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
- Keep section and page pane data types separate. The black pane recursively flattens only `SectionNode`; the gray pane either lists direct `NotePage` items or a separate recursive stream of section headers plus owned pages. A deep-page selection must not silently change the black-pane root.
