# Live Mojian note synchronization

Last verified: 2026-10-12

## Authorized deployment and initial source

The user requested the Android build on Redmi K70 and Xiaomi Pad 6 Pro and Syncthing between
these two devices and OMEN. The initial version must come from OMEN. The live Windows workspace
is `C:\Users\Meta\Documents\Mojian\_migration\native-20261002\workspace`.

Both Android devices receive folder ID `Mojian` at `/storage/emulated/0/Documents/Mojian`.
Seed with Windows send-only and Android receive-only. Switch to ordinary send/receive only
after complete initial transfer, canonical-store compatibility and a disposable round trip.
Do not seed from Android's sample library. Preserve existing Syncthing identities and folders.

Ignore device-local `settings.json`, `.previous` recovery copies and temporary writes.
Use `(?d)*.tmp` and `(?d)*.previous` so these local copies cannot block a remotely deleted
notebook directory. Keep root settings excluded. Ignored files otherwise prevent deletion of
an empty synchronized directory; see [Syncthing's ignore documentation](https://docs.syncthing.net/users/ignoring.html).
Keep all source notebook/page/asset identifiers and imported vector/layer metadata.
One active writer per notebook remains the version-one rule; Syncthing conflict files must
remain available. Do not claim an application-level merge of simultaneous edits.

## Shared storage contract

Both clients read and write the existing Windows version-two folder format: `library.json`,
`notebooks/<notebook-id>/pages/<page-id>.json`, and page-owned assets. Document geometry is in
96-DPI units. Android's authoring units are converted at this boundary; viewport coordinates
never enter the shared format. The old `.mjn` package was an exchange format, not proof that
the Android process-session model had been connected to this durable workspace.

Android `SharedWorkspaceCodec` maps stable numeric paper/background/object enums and retains
unknown source metadata. Existing IDs, original filled paths, affine transforms, layer
visibility/order, source colors, PDF references, tags and text remain available after edits.
`SharedStrokeCodec` renders existing paths rather than re-tessellating imported point batches.
New native handwriting stores its points, pressure, timing and actual filled outline so Windows
can display the same stroke. Android rich-text metadata is retained as an extension alongside
canonical text/style fields; Windows edits invalidate a stale mobile rich-text representation.
The current renderer does not provide full rich-text run or arbitrary layer-editing parity.

Android selects the received workspace at `/storage/emulated/0/Documents/Mojian`; its File /
Options / Syncthing page can select another complete workspace and open the Android shared-file
permission settings. Direct shared-file access requires Android 11 or newer. Private settings,
tool presets, viewports and navigation stay device-local. The private sample library is not
used to seed a shared folder.

Completed freehand, tags and text now participate in durable page snapshots. A serial worker
performs JSON/file writes, with lifecycle/navigation/export flushes and atomic temporary-file
replacement. Android and Windows retain one local `.previous` revision. A read/restore does
not trigger content changes or alter original newlines, color metadata or paper extents.
Unreadable or not-yet-received data must not be saved as an empty replacement.

Both apps check incoming file generations and refresh an idle workspace every three seconds,
retaining their local navigation. A stale document is refused at save time; background-write
failures block automatic reload rather than discard unsaved edits. File delivery can precede
an active editor's reload. Finish editing and wait for Syncthing before handing the same notebook
to another device. This is file-based synchronization with one active writer, not a concurrent
operation-merge protocol. Syncthing conflict copies and ten simple file versions are retained.

## Required acceptance

- Both devices contain the complete OMEN library with the same IDs, page count and assets.
- Applications load the received library rather than their private sample libraries.
- Durable handwriting and supported mixed content survive process restart.
- Canonical writes preserve unsupported source fields and never replace unreadable data with empty data.
- A disposable change transfers between real devices and can be reopened by the destination app.
- Device-local view/tool settings remain local. Runtime/pen acceptance is distinct from file transfer.

## Acceptance record

On 2026-10-12 the complete OMEN seed contained five notebooks, 652 pages and 1,793 synchronized
files / 5,285,164,934 bytes after ignores. The initial receive-only devices were reset to that
source before bidirectional editing was enabled. All three `Mojian` folders now use send/receive;
existing device IDs and unrelated folders remain. The database free-space threshold was changed
from 1% to 1 GB when Windows had only 2.1 GiB free. The separate disk-cleanup task later freed space.

Windows and Android are version 0.6.0; Android is `com.meta.inklab.debug`, version code 7.
The APK was updated in place on Redmi K70 and Xiaomi Pad 6 Pro. Actual application views on
both devices displayed the received Chemical Kinetics handwriting, not the private sample data.
Original homework 1 retains 1,110 strokes / 26,821 points; homework 2 retains 1,105 strokes
and its source text. Runtime read checks also cover imported Notein paths and nullable PDF metadata.

A disposable canonical notebook/page was created on OMEN only. The phone authored one native
40-point stroke and text in MainActivity, then cold-reopened them. The tablet read that page and
appended a three-point native stroke. Windows' actual store/canvas read both strokes and rendered
their filled vector outlines, then added text. Both Android application views displayed that
OMEN addition. An additional phone text edit reached OMEN after committing the configured input
method's composition. The disposable notebook and its test conflict copy were removed after
acceptance. The current five-book / 653-page library includes a real OMEN page added during
deployment; cleanup removed only the test notebook and did not restore an older whole library.
Final readback: OMEN, phone and tablet each report 1,794 local/global files, idle state,
zero needed files/bytes and zero folder errors. Both Android test directories are absent.

Windows 161 tests pass, including real-window/clipboard checks and incoming-file/stale-writer
regressions. Android 162 unit tests pass; debug APK, separate test APK and lint build successfully
(zero lint errors, 130 warnings). Native debug probes exercise outline rendering, source IDs,
pressure, movement and read-only text restoration. Physical stylus feel remains user acceptance;
these probes do not measure hardware pen latency.

Large received pages must be parsed once per file generation. Display/export consumers reuse
a weakly cached document, and cache replacement follows successful writes. Page snapshots keep
a small streaming content fingerprint rather than another full-page string; JSON writes stream
to the temporary file. Inactive page sessions release their content graphs but retain viewports.
Parsing a second copy of a 59,564,124-byte imported page caused an observed 512-MiB Android heap
exhaustion. Textual comparison also spent about 88 seconds formatting/escaping that source
graph. Typed streaming comparison reduced the measured load to about 9 seconds on the phone;
the complete native read/decode/render probe passed on both devices in about 15-16 seconds,
retaining 10,245 strokes / 402,710 points. The real MainActivity also opened the source page
and displayed its image assets. Offscreen assets are rejected before bitmap decoding.
Do not restore the previous double-parse implementation or an unbounded strong page cache.

The final installed APK includes the separate stored-ink appearance and captured-navigation
repairs. Both devices' native color probes pass all twelve RGB cases and ten source-alpha cases,
with source JSON preserved. The separate gesture instrumentation APK is compiled but Xiaomi
rejects its installation; physical pen/button interaction remains user acceptance.
