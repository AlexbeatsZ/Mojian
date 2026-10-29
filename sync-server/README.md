# Mojian native HTTPS sync

An independent, dependency-free Node 22+ service for the existing Mojian folder
format. Exact Android/Windows wire contract and integration requirements are in
[PROTOCOL.md](PROTOCOL.md). No credentials are shipped in source or client binaries.

## Existing Sakura Web endpoint

Windows and Android prefill `https://cdn.u3146344.nyat.app:63573/mojian-sync/v1/`;
sync is still opt-in and requires a separately provisioned secret and dedicated copy.
Prefilling this address does not deploy a backend or enable the live notes.

ROG currently shares Web ingress between Dayweave and subscription distribution.
`gateway-route.mjs` streams only exact Mojian v1 routes to loopback 18742;
`shared-gateway.mjs` wraps the existing Dayweave gateway without changing other handlers.
It preserves Authorization, blocks browser-origin requests and enforces backend upload limits.

On the ROG checkout run `powershell -NoProfile -File sync-server/ops/install-rog.ps1`
to inspect the plan without writing or restarting anything. **Only after user confirmation**,
add `-Apply` to create the dedicated startup task/private token and restart the shared gateway.
The gateway task must still match the inspected module or the installer refuses to change it.
Private config/data and gateway-task recovery XML live in ignored `.local/native-sync/`.
The token is never printed, passed on a command line or committed. Keep the entire data root
backed up; clients require the same secret entered in their local sync settings.

Verify real public HTTPS authentication, upload/commit/download between disposable clients,
and preservation of existing Dayweave/subscription routes before connecting notes. The installer
does not enable clients or copy/seed the user's library. See `../docs/design/native-sync.md`.

## Run a separately configured service

Use the existing Node runtime. No npm install or global tool is required.
Configure `MOJIAN_SYNC_DATA` to a private, dedicated persistent data directory,
`MOJIAN_SYNC_TOKEN` to a secret of at least 32 bytes, and optionally
`MOJIAN_SYNC_PORT` (default 18742), then run `node server.mjs`.
The listener binds only to `127.0.0.1`. Expose `/mojian-sync/v1/` using a separately
configured HTTPS reverse proxy, preserving Authorization and allowing streaming
uploads. Disable access-log recording of authorization credentials. Public
ingress, TLS, service installation and credentials are operator configuration;
the code does not configure them or contact any existing server.

One process owns each data root. `state.json` atomically stores the current
manifest and every operation receipt/request digest. `history/` retains previous
manifests; `blobs/` contains immutable content. Back up the complete root while
stopped or using a filesystem-consistent snapshot. Do not reset workspaceId or
delete receipt history while clients retain checkpoints. A restart recovers a
lock owned by an exited process; a live or invalid lock is conservatively refused.
If a PID has been reused, inspect ownership before manually removing a stale lock.
After a state-write failure the running server refuses further requests until
restart, rather than serving potentially stale memory after an uncertain write.

Tombstones, receipts, history and blobs have no garbage collection in v1. Monitor
capacity and back up before any future retention/migration work. There is no
multi-user tenancy, end-to-end encryption, collaborative merge, account service,
or automatic conflict resolver in this version.

## Windows host integration

The client, models and local persistence helpers are in
`mojian/src/Notein-win/Services/NativeSync*.cs`; they have no WPF or WorkspaceStore
dependency. The application owns configuration, successful local save barriers,
foreground scheduling, cancellation, UI dispatch and repository reload.

1. Save the existing workspace, then explicitly clone it with
   `CloneWorkspace(source,target)` into a new path outside Syncthing. The original
   remains untouched; a failed copy has no usable marker and must not be activated.
2. Load/save `NativeSyncConfiguration`. `Enabled` is false by default. Set an
   HTTPS endpoint ending `/mojian-sync/v1/` and the operator-provided token only
   after the user selects the dedicated copy. Never log the token.
3. Before constructing/loading a WorkspaceStore, check for
   `.mojian-sync/incoming.json`. Construct the client and call
   `RecoverPendingIncoming()` or `RecoverPendingIncomingAsync(applyOnIdle)`.
   These never perform network requests. Async incoming preparation explicitly runs
   on a worker, even when called from a UI context; the synchronous convenience
   still blocks its caller and is only for startup before opening the repository.
   False, or a remaining incoming journal,
   means that workspace must remain unavailable for editing until recovered.
4. After local saves succeed, call `SyncAsync(applyOnIdle, cancellationToken)` on
   foreground/resume and periodically while foreground. Return false from the
   callback while input, unsaved changes or persistence work is active. Otherwise
   hold the same workspace mutation gate as edits/deletion, execute Action and
   reload the repository before releasing it. The client applies all files with
   library.json last. Hashing, backups, replacement copies and checkpoint serialization
   finish on a worker before the callback. Action only validates final metadata and
   atomically publishes the prepared files/checkpoint; existing files use `File.Replace`
   so delete-shared readers do not prevent Windows replacement. The callback owns
   dispatch to the UI thread and must not claim success without executing Action.
   Cancellation is checked when a queued Action starts, before any publication;
   once publication begins, cancellation does not interrupt the batch midway.
   A changed local precondition throws rather than silently returning from Action;
   propagate that failure so the host cannot mistake it for a successful reload.
   If publication fails and `HasPendingIncoming` remains true, pause editing of
   that workspace until local recovery succeeds; the same rule applies during an
   existing session, not just at startup. A callback/repository-reload exception after
   Action completed can leave a fully acknowledged batch with no journal; the host
   must still finish reloading before allowing edits, not blindly reapply that batch.
5. Bind `Snapshot`/`SnapshotChanged` for separate pending, offline, conflict,
   synced, paused, waiting-for-idle and error states. Events may be on a worker;
   dispatch to UI. `Status`/`StatusChanged` remain compatible text APIs.
6. Cancel and await the active call before disposing or switching workspace.
   There is no requirement for a final exit-time upload. The next foreground
   scans durable local files and replays any submitted outbox with its original ID.

Incoming backups and both sides of conflicts live under `.mojian-sync/`.
`RecoveryDirectory` and `Snapshot.ConflictDirectory` expose recovery locations.
A conflict pauses synchronization across restart. Preserve the entire workspace
and this directory for reviewed recovery; deleting state to "resolve" a conflict
can discard version knowledge and resurrect deleted notes. V1 intentionally does
not activate a last-writer-wins resolution.

Syncthing detection rejects `.stfolder`, `.stignore` and `.stversions` in the root,
ancestors and descendants, plus filesystem links/junctions. This detects normal
Syncthing workspace layouts; an operator who removes its markers must still not
configure two engines on the same directory. Rechecks occur before every pass and
incoming publication. Native activation is never automatic.

## Isolated automated validation

`node --test test/server.test.mjs` tests the backend alone. The Windows integration
scenario is explicitly skipped unless `MOJIAN_NATIVE_SYNC_TEST_DLL` is set.

`node test-integration.mjs` builds the package-free .NET 10 harness in an isolated
temporary `.agents` directory and runs all backend and Windows scenarios against
an actual ephemeral loopback listener. It removes its build directory afterward.
It does not run the WPF app or touch existing workspaces/configuration. Tests
cover strict CAS, atomic batches, replay of original receipts after later commits
and process death, request-ID misuse, tombstones, conflict copies, offline outbox,
lost responses with newer edits, shared-hash downloads, partial incoming replay,
corrupt recovery staging, worker/prepared publication boundaries, actual partial
replacement failure and offline restart recovery, queued cancellation, callback
failure boundaries, authentication classification and migration guards.

`test/fixtures/v1-wire.json` is the shared golden UTF-8/hash/commit/receipt/
deletion/replay fixture. Both Node tests and the Windows harness consume this
same file; the latter compares the actual serialized client request and then
exercises the real Node listener. Android can consume the identical fixture;
Android producer compilation alone is not cross-language wire acceptance.

Recorded on 2026-10-28 with Node 24.14.1 / .NET SDK 10.0.400:
`MSBUILDTERMINALLOGGER=off node test-integration.mjs` completed with 11/11 Node
tests (10 backend tests plus the Windows integration wrapper), no failures/skips,
and 24/24 Windows client scenarios. The harness uses the real loopback service,
with offline journal/callback cases intentionally making zero HTTP requests.
The isolated .NET build explicitly reported zero warnings and zero errors; no
Node runtime warnings were emitted. Build outputs and test workspaces were removed.
Android execution of this golden fixture, WPF host build/runtime, public TLS
ingress, and production-size library performance are outside that evidence.

These tests do not constitute real Windows UI, Android, public HTTPS, proxy or
physical-device acceptance. No deployment is performed by test commands.
