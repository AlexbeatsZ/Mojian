# Notein Windows and Syncthing Feasibility

Last verified: 2026-08-22

## Objective

Provide a Windows writing workflow for the user's own Notein notebooks without depending on Notein cloud sync. Syncthing should carry user-controlled files, while existing tablet notes remain recoverable.

This document is an interoperability assessment and a clean-room design boundary. It does not contain or redistribute Notein application code, signing material, or user note content.

## Evidence Snapshot

### Installed application

- Device: Xiaomi `23046RP50C`, Android 15 / API 35, arm64.
- China package: `com.orion.notein`.
- Installed build: `v2.0.10.0`, version code `343`, minimum API 26, target API 35.
- APK: one 114,190,617-byte base APK, non-debuggable, `primaryCpuAbi=arm64-v8a`.
- The APK contains six DEX files and native libraries including AndroidX Ink (`libink.so`), PDFium, OpenCV, SQLiteX, and several other arm64-only components.
- The application class is `com.sagittarius.v6.StubApplication`; business classes such as `com.orion.notein.MainActivity` are declared in resources but are not present as ordinary decompilable classes. The APK contains Baidu Protect assets and native code. This is consistent with runtime code protection.

### Storage

- `run-as com.orion.notein` is rejected because the production app is not debuggable.
- The authoritative private directory is `/data/user/0/com.orion.notein` and is not readable over ordinary ADB.
- The app-specific external directory `/sdcard/Android/data/com.orion.notein/files` was about 256 MiB with 5,638 files at inspection time.
- Its top-level files consisted of 5,102 UUID-named files without extensions and 534 `.png` files. Twelve evenly distributed no-extension samples plus two manually selected samples all had a PNG signature. These are previews/caches, not a complete editable notebook store.
- Runtime logs name a `NoteContentDatabaseProvider` and show it maintaining content reference counts. This confirms that editable note state depends on a private database/data layer in addition to external PNG previews.

### Supported backup path

Decoded resources mention `Internal storage/Documents/Notein/backup`, but the observed 2.0.10.0 local-backup flow actually opens Android's system Save-As picker with the default filename `backup.zip`. The user-authorized snapshot was saved as `/sdcard/Download/backup.zip` and completed successfully. The same resources say imported backups coexist with current notes rather than replacing them.

The resulting 1,579,602,577-byte file is an unencrypted, monolithic full-library ZIP with 167 per-note `.in` nested ZIPs, two `.template` entries, and one `.tree`. Each inspected `.in` contains a Room/SQLite v25 database, JSON metadata, and the original source PDF when applicable. This is a usable import boundary, but the outer full-library snapshot is not a live merge format.

The resources also distinguish three separate mechanisms:

- cloud sync;
- cloud-drive backup;
- local backup/import.

They must not be treated as interchangeable.

## Feasibility Decision

### Direct source port: not practical

The installed product is a protected native Android application, not a Flutter, React Native, or web application with a reusable Windows UI layer. It depends on Android APIs, an arm64 Ink native library, Android PDF/native graphics libraries, account SDKs, and OEM services. Recompiling the APK into a native Windows application is not a realistic engineering route.

Circumventing the runtime protector would still produce Android-oriented implementation details rather than a maintainable Windows codebase. It would also cross the clean-room and product-terms boundary, so it is not part of the proposed work.

### Android runtime on Windows: possible but limited

Running the official APK inside an Android VM/emulator is technically possible if the runtime provides ARM translation and acceptable pen input. This is the shortest path to seeing the same UI on a PC, but it is not a port.

It does not solve user-controlled live synchronization:

- Syncthing in the VM cannot normally read Notein's private database.
- Root-level copying of a live SQLite/WAL database can produce inconsistent snapshots.
- Simultaneously editing the same notebook on tablet and VM has no known merge protocol.
- Stylus pressure, tilt, palm rejection, file pickers, and hardware acceleration require real runtime acceptance testing.

This route is acceptable only as an isolated convenience client, preferably with Notein's own sync. It does not meet the requested Syncthing-first design on its own.

### Clean-room Windows companion: feasible for import; Notein write compatibility remains conditional

The backup experiment resolved the first uncertainty: the container is open and structurally parseable. The clean-room route should now treat the app-supported backup as a read-only interoperability boundary:

1. Keep the tablet-created backup as a restorable snapshot and inspect only copies.
2. Build a read-only parser for the outer ZIP, per-note ZIP, metadata, Room entities, and both stroke families.
3. Render selected disposable pages and compare them with Notein PDF/PNG exports.
4. Define a clean, product-owned notebook model rather than using Notein's monolithic outer ZIP as live state.
5. Only after rendering is correct, generate a modified copy and import it as a coexisting notebook.
6. Never test write-back against the only copy of a real notebook.

The possible outcomes are:

| Backup result | Windows scope |
| --- | --- |
| Open container with documented-enough vector/page records | **Observed.** Read-only viewer/importer and an independent editor are feasible. |
| Open container but proprietary brush records | **Partly observed.** Legacy points are explicit; new strokes are parseable, but the complete private `notein-fountain-v1` brush graph is not persisted. |
| Encrypted or signed container with keys only in private app state | Safe write-compatible client is not feasible without vendor cooperation; use a PDF-based bridge or emulator. |
| Monolithic full-library snapshot | Import/export can be automated, but it is not suitable for concurrent Syncthing merges. |

## Proposed Syncthing Architecture

Do not sync `/Android/data/com.orion.notein/files`; it is mostly previews and omits the private database.

Use two layers:

1. **Exchange layer** — Notein-generated `backup.zip` files saved through the system picker. Syncthing may copy renamed/versioned files as immutable snapshots.
2. **Canonical PC layer** — one directory per notebook, owned by the companion app, for example:

```text
notebooks/
  <notebook-id>/
    manifest.json
    pages/
      <page-id>.json
    assets/
    source.pdf
    revisions/
```

The PC app imports a Notein snapshot into the canonical layer and exports a new Notein-compatible snapshot only when explicitly requested. Syncthing operates on the canonical layer and immutable exchange snapshots, not on a database that Notein currently has open.

### Conflict rules

- A notebook has one active writer at a time until the format's stable object IDs and merge semantics are proven.
- Each export records parent revision, source backup identity, app/schema version, and content hash.
- Divergent edits create a sibling conflict copy; never use last-writer-wins silently.
- Tablet imports must create a new/coexisting notebook during early testing.
- Deletions use tombstones and a retention period rather than immediate propagation.

## Minimum Useful Product

If the test backup is open enough, build in this order:

1. Windows library and page viewer.
2. PDF export with visual comparison against Notein.
3. Basic pen/eraser/highlighter input with Windows Ink.
4. New-notebook export and tablet import.
5. Existing-notebook round trip using a disposable notebook.
6. Syncthing folder watcher, revision history, and conflict copies.
7. Additional Notein tools only after format-level acceptance tests exist.

This is a new compatible product, not a source-code port. The first deliverable must be a real end-to-end round trip, not a UI mockup.

## Next Required Experiment

Prove a read-only renderer against disposable content:

1. Identify one user-designated disposable note/page in a copied backup.
2. Decode both legacy `record_json` and new `ink_stroke_blob` inputs without displaying unrelated note content.
3. Render page background, layers, and strokes from the copy.
4. Compare geometry and bounds with a Notein PDF/PNG export.
5. Record unsupported entities and visual differences before attempting any generated import.

## Current Conclusion

- Native Windows port of the APK: **no**.
- Same APK in an Android runtime: **yes, with compromises**, but Syncthing cannot safely live-sync the private database.
- Independent product plus Syncthing: **yes as a clean-room notebook application** using its own per-notebook files and conflict model.
- Read-only Notein import: **feasible** from the observed open ZIP/SQLite backup.
- Notein-compatible write-back: **still conditional** on a disposable render/round-trip test; it should not block development of the independent handwriting engine.
