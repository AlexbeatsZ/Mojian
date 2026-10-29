# Mojian native sync v1

This contract is shared by Windows and Android. Native sync is opt-in, only in an
explicitly created dedicated copy outside every Syncthing directory. It never
enables itself for an existing workspace. No credentials or endpoint are shipped.

## Transport

Clients may configure multiple ordered addresses of this same workspace. A pass always starts
at the first configured address and falls back in order on transport/service failures. This does
not change wire requests, CAS, device IDs or stable operation IDs. Before replaying an uncertain
operation through a new address, verify the workspace identity and nondecreasing revision. A
different workspace, rollback, content conflict or local recovery error is not a route failure.
The persisted `endpoint` remains the checkpoint binding; an optional `addresses` list changes
transport routes only. No address sorting, racing or successful-route promotion is permitted.

The configured base URL ends in `/mojian-sync/v1/`. Clients require HTTPS, except
HTTP on a loopback host for isolated tests. Disable redirects; never forward the
Bearer token to another host. Every request requires `Authorization: Bearer TOKEN`.
The Node service listens on loopback; a separately configured TLS reverse proxy
may expose it. This service is independent of any subscription distributor.

All JSON uses UTF-8 and camelCase. Hashes are lowercase SHA-256 hex of the exact
file bytes. Revisions and versions are nonnegative safe JSON integers. IDs are
16-80 ASCII letters, digits, `_` or `-` (UUIDs are valid). There is one workspace
per server data root, with an immutable `workspaceId`. A changed identity or a
decreasing server revision must pause clients rather than reset their baseline.

## Files and manifest

`GET manifest` returns:

```json
{"workspaceId":"server-uuid","revision":12,"files":{"library.json":{"hash":"64 lowercase hex characters","version":12},"notebooks/book/pages/page.json":{"hash":null,"version":10}}}
```

Only `library.json`, `notebooks/<id>/pages/<id>.json` and
`notebooks/<id>/assets/<id>/<filename>` are synchronized. Components start with an
ASCII letter, digit, `_` or `-`; subsequent characters may additionally be `.`.
Trailing dots, Windows reserved device names, paths of 500+ characters, `.tmp`,
`.previous` and `-sync-conflict-` files are forbidden. Paths are case-sensitive
on the wire but cannot collide when compared case-insensitively. Local settings,
native-sync state, recovery files, source backups and credentials are excluded.

An absent entry has version 0 and hash null. A deleted entry is a retained
tombstone `{hash:null,version:N}`; entries/tombstones are never pruned in v1.

`PUT blobs/<hash>` streams raw bytes. Success: `200 {"hash":"..."}` after
durable storage. Repeated uploads are safe; wrong hash returns 422. Blob size
defaults to 1 GiB; oversize returns 413. `GET blobs/<hash>` streams exact bytes
with Content-Length, or 404. Clients verify the hash before publishing a download.
Blobs are immutable and not garbage collected in v1; old content remains available.

## Atomic, idempotent commit

`POST commit` (maximum 4 MiB, 1-20000 changes):

```json
{"workspaceId":"server-uuid","deviceId":"persistent-device-uuid","operationId":"persistent-operation-uuid","changes":[{"path":"library.json","expectedVersion":12,"hash":"64 lowercase hex characters"}]}
```

Upload all non-null blobs first. `hash:null` means deletion. Paths must be unique.
The server compares **every expectedVersion exactly**, even if the current hash
equals the proposed hash. All changes commit or none do. An accepted operation
increments revision once and gives every changed entry that revision as its version.

Success `200` is an immutable receipt containing **only the affected paths**:

```json
{"workspaceId":"server-uuid","operationId":"persistent-operation-uuid","revision":13,"files":{"library.json":{"hash":"64 lowercase hex characters","version":13}}}
```

The manifest and receipt are written in the same durable atomic server state.
The key is `(deviceId,operationId)` within this workspace. Retrying the same
logical request (change order ignored) returns the original receipt, even after
other commits or a server restart, without checking CAS again or advancing a
version. Reusing the key with different changes returns 409 `operation_reused`.
There is no receipt expiry in v1.

Errors are JSON `{error:"code",...}`:

- 400 `invalid_commit`, `invalid_json`, `invalid_path`: malformed input.
- 401 `unauthorized`: credentials/configuration error, not an offline indication.
- 409 `workspace_mismatch`: different workspace identity.
- 409 `version_conflict`, with `conflicts:[paths]` and current `revision`: no change applied.
- 409 `operation_reused`: same operation key with a different payload.
- 413 `request_too_large` / `blob_too_large`.
- 422 `blob_missing` / `hash_mismatch`.
- 500 `storage_failure`: do not assume the commit failed; retry the SAME operation.

## Required client persistence and lifecycle

The existing repository saves local edits first, independently of networking.
Before contacting the server, scan durable local files, capture changed bytes
as immutable staging blobs, and persist an outbox with a stable operation ID.
The local files themselves plus checkpoint are authoritative for edits made
while the process was stopped. Never depend on an exit-time upload.

Before a first submission, fetch the manifest and compare local, acknowledged
baseline and remote content. Equal content can be acknowledged without upload.
Both sides changed means conflict; preserve both contents and explicit deletion
records. Do not rebase or silently overwrite a conflicting remote version.
Persist the final operation before POST. Once POST might have been attempted,
replay that exact operation until a receipt or a definitive conflict is known.
Advance checkpoint only from a validated receipt; then remove the outbox.

Downloads are staged and hash-verified. Persist an incoming plan containing
expected local hashes and target entries before publishing files. Apply only
behind the repository's idle/save barrier, validate every local precondition,
then replace assets/pages before library.json. Retain backups and replay the plan
after restart; a file already matching the target is complete. An unexpected
local edit blocks replay and is preserved as a conflict. Repeated paths sharing
a hash must not consume the staging blob. Advance the checkpoint only after the
entire incoming plan finishes. Do not open an editor on a partial incoming plan.
If an in-session apply fails and leaves a journal, the host must pause editing
until local recovery succeeds, as it would at startup.

Foreground/resume: flush local repository, call SyncAsync; repeat periodically
while foreground. Pause: cancel network work without deleting checkpoint/outbox.
The host serializes workspace writes with the incoming callback and reloads its
repository after a successful apply. It must not interpret a transport failure
as local-save failure. Show separate pending, synced, offline, conflict, paused,
waiting-for-idle and configuration/storage-error states.

## Windows integration API

`NativeSyncConfiguration.Load(root)` / `Save(root)`, with `Enabled=false` by
default and explicit Endpoint/Token. `NativeSyncClient.CloneWorkspace(source,
target)` creates a stable dedicated copy and marker; source remains untouched.
`RequireDedicatedWorkspace(root)` rejects Syncthing markers in ancestors or
descendants, links/junctions and missing native-copy marker.

`new NativeSyncClient(root, endpoint, token, handler = null)` opens a single
writer lock and checkpoint; constructor performs no network request.
`Task SyncAsync(Func<Action,Task<bool>> applyOnIdle, CancellationToken)` performs
one serialized pass. The callback must flush successful saves, exclude concurrent
workspace mutations while executing Action, and return false when busy. On startup
call this before allowing workspace edits if `HasPendingIncoming` is true.

Public read-only `Snapshot` (`NativeSyncStatus`) and `SnapshotChanged` expose
`Phase`, `Message`, `PendingFiles`, `ConflictDirectory`, `LastSuccessUtc`.
`Status` and `StatusChanged` remain available for a simple text binding.
`RecoveryDirectory` exposes the local recovery root. `HasPendingIncoming` lets
the host guard startup. `RecoverPendingIncomingAsync(applyOnIdle, cancellation)`
only replays an existing local journal, with no network or outgoing scan; false
means the host must not open an editable workspace. `RecoverPendingIncoming()`
is the synchronous startup-only convenience before constructing a repository.
Both use the same serialized gate as SyncAsync. Dispose after cancellation/await of the running pass.
Events can run on a worker thread; the host dispatches UI updates.

Async incoming preparation explicitly runs on a worker independently of the caller's
SynchronizationContext: blob/local hashing, backups, replacement copies and checkpoint
serialization finish before `applyOnIdle`. The callback owns UI dispatch. Its Action
only checks final metadata and publishes prepared replacements/deletions and the staged
checkpoint, with `library.json` last; existing Windows targets use atomic `File.Replace`
to support readers that allow delete sharing. The synchronous startup convenience still
blocks its caller while worker preparation finishes.

A queued Action checks cancellation before publishing anything; once publication starts,
cancellation does not interrupt the batch between files. A changed local precondition
throws before publication instead of silently returning, so the host can keep editing
locked and recover the remaining journal. Returning true without executing Action is
not a completed batch. Propagate Action and repository-reload exceptions. If a callback
throws after Action completed, the files/checkpoint may already be acknowledged and the
journal removed; the host must finish reloading before enabling editing, not reapply an
already committed incoming batch.

Windows repository writers and the engine share `NativeSyncMutationEpoch.ForRoot(root)`.
Use `BeginMutation()` before the first file change and dispose it after the entire
save or move/delete-to-library-publication transaction (including failure recovery).
Nested mutations are supported. The engine captures an idle epoch, scans and stages
immutable blobs without holding an epoch or repository lock, then uses the short
`PublishIfUnchanged(epoch, publishOutbox)` barrier. An active or intervening mutation
rejects the snapshot, clears cached hashes and defers the pass without HTTP or a new
outbox. Writers never wait for hashing, blob copies or network work. Only final small
outbox publication is under the epoch lock. Later edits cannot change staged bytes.
Incoming journal publication retains the repository file gate and a mutation scope;
neither gate is held across await or UI dispatch. Android's `LocalMutationEpoch`
provides the same outgoing consistency contract. Per-file hashes alone cannot prevent
old-library/new-tombstone batches. These epochs are in-process; startup must recover
durable repository deletion journals before opening or scanning the workspace.

V1 content paths remain ASCII-only as specified above. Scan and migration must refuse
unsupported real content beneath `notebooks/<id>/pages` or `assets`, including Unicode
extensions or unexpected nesting, instead of silently omitting referenced data.
Explicit `.previous`, `.tmp`, `-sync-conflict-` and private recovery paths remain excluded.
A rejected migration never writes the dedicated-copy marker or changes its source;
a partially copied unmarked destination may remain for inspection.

Conflicts stay blocked and recoverable on disk; v1 does not invent an automatic
resolution. The UI can open the recovery directory; manual resolution requires
an explicit reviewed workspace recovery, not clearing a checkpoint blindly.
