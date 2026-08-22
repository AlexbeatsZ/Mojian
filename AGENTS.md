# Goal

- Assess whether the Android Notein app on the user's tablet can be brought to Windows with PC-side writing and file-based synchronization through Syncthing instead of Notein cloud sync.
- Preserve the user's existing Notein data. Reverse engineering is for interoperability with the user's own notes; do not publish proprietary app code or keys.

# Current State

- Repository is new and has no commits or remote.
- Tablet is reachable through wireless ADB at `192.168.31.243:37739` (Xiaomi `23046RP50C`, Android 15 / API 35, arm64).
- Installed China package: `com.orion.notein`, `v2.0.10.0` (`versionCode=343`, `minSdk=26`, `targetSdk=35`), single 114,190,617-byte APK, installed from Xiaomi Market.
- APK is non-debuggable and app-private `/data/user/0/com.orion.notein` cannot be read with `run-as`.
- App-specific external files are readable at `/sdcard/Android/data/com.orion.notein/files`; current snapshot is about 256 MiB / 5,638 files, mainly UUID-named blobs and PNGs.
- Static scan shows a native Android/Kotlin/Java app, not Flutter/React Native. It includes arm64-only native libraries such as `libink.so`, PDFium, OpenCV, SQLiteX, and Baidu Protect. Xiaomi CloudKit database/file sync providers are bundled.
- Runtime resources and logs show that editable data depends on a private `NoteContentDatabaseProvider`; the readable external directory is predominantly PNG previews.
- Notein's app-supported local backup path is `Internal storage/Documents/Notein/backup`. No local backup was present at inspection time.
- Design/feasibility record: `docs/design/sync-architecture.md` (read before synchronization or note-format work).

# Active Work

- [x] Establish ADB access and identify the exact installed package/version.
- [x] Capture first-pass APK/runtime/storage architecture evidence.
- [x] Decompile resources far enough to locate the supported local-backup path and distinguish it from cloud sync/backup.
- [x] Inspect representative user-owned external blobs without exposing note content; sampled UUID blobs are PNG previews.
- [x] Determine initial feasibility: direct source port is not practical; a clean-room companion is conditional on the supported backup format.
- [x] Write the synchronization design boundary before implementation.
- [ ] Create a disposable Notein notebook and local backup on an unlocked tablet, then inspect the backup container.
- [ ] Prove a read-only parser/renderer before any write-back experiment.

# Build / Run / Test

- ADB binary: `C:\Users\Meta\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- Verify device: `adb devices -l`.
- Installed package path: `adb shell pm path com.orion.notein`.
- APK inspection tools currently available: Android SDK `apkanalyzer`, Java 17. `jadx` is not globally installed; use a temporary portable copy if needed.
- Keep device probes read-only until a tested backup/restore strategy exists. Do not enable Notein cloud sync or write into its live data directory during format discovery.

# Durable Lessons

- Notein metadata and document blobs are split across Android app-private and app-specific external storage. Syncthing of only `/Android/data/com.orion.notein/files` is not yet proven complete or safe.
- The installed China build differs from the global Play package (`com.orion.notein.global`); do not assume global-package paths or backup behavior apply.
- Launching Notein can immediately activate configured Huawei Drive/analytics components. Keep it force-stopped during static work and avoid launches that are not needed for a deliberate local-backup experiment.
