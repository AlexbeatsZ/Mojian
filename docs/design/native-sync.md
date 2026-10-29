# Native HTTPS synchronization

Implementation baseline: 2026-10-28. This is an opt-in Windows/Android transport for the
canonical folder format, not a replacement automatically applied to the deployed Syncthing
library. The iOS client does not implement this transport. See `sync-server/PROTOCOL.md` for
the exact v1 wire contract and `sync-server/README.md` for operator setup and isolated tests.

## Sakura shared ingress (2026-10-28)

Windows/Android now prefill `https://cdn.u3146344.nyat.app:63573/mojian-sync/v1/`.
The hostname uses the existing Sakura Web public endpoint with certificate-verifiable HTTPS;
no access token is embedded and sync remains disabled by default. Android workspace selection is explicit; see
`android-folder-selection.md`.

Read-only ROG inspection found that port 18781 is already the Dayweave shared gateway, which
delegates subscription routes to 18782 and fixed Dayweave routes to 49732. Never replace it with
the Mojian backend or restart SakuraFrpService. The prepared `shared-gateway.mjs` wrapper imports
the original gateway module and delegates every non-Mojian request to its unchanged handler.
Only exact v1 manifest/commit/blob paths are forwarded to the dedicated loopback backend on
18742, retaining Authorization and streaming uploads up to the backend limits.

`sync-server/ops/install-rog.ps1` is prepare-only unless `-Apply` is explicitly supplied. Actual
application requires user confirmation for a new startup task and restarting the shared gateway.
It creates a private token/data directory with restricted ACL, saves the old gateway task XML,
starts `MojianNativeSync`, then changes only the gateway task action. A gateway-task update
exception restores its original action. Post-start public TLS/auth/round-trip and unchanged-route
checks remain separate acceptance requirements. No current library is seeded automatically.

The user explicitly authorized deployment on 2026-10-28. `MojianNativeSync` and the gateway
wrapper are running on ROG; the Sakura service, publisher task and existing data are unchanged.
The backend/private configuration lives under the ROG Mojian checkout's `.local/native-sync/`.
Public verification first used a disposable backend under `%TEMP%/.agents/`, not the production
data root. All 24 real Windows client scenarios passed through the certificate-verified public
URL. Eight subscription responses remained byte-identical and invalid Dayweave usage requests
were rejected identically through the gateway and its loopback service. An opt-in JVM test of
the actual Android client/HTTP transport also passed upload, receive, return edit and tombstone
through the public URL. Production was restored to its untouched data root (revision 0/files 0)
after testing; temporary test backend data was not promoted. Physical Android and live-library
adoption remain separate from these transport checks.

## Workspace and credentials

- The existing workspace remains authoritative until the user explicitly creates/selects a
  dedicated clone. A new `.mojian-native-workspace` marker identifies the clone. Reject existing
  destinations, source descendants, links/junctions and detected Syncthing folder boundaries.
  A failed clone is not activated. No code discovers or rewrites an existing server configuration.
- Syncthing and native sync must never operate on the same tree. Keep the original files,
  Syncthing identities, ignore rules and service unchanged. Marker detection is conservative
  protection, not proof that an external synchronizer has never been configured without markers.
- Credentials are local app settings, outside the synchronized tree. Never commit, embed in
  exports or log them. HTTPS is required; loopback HTTP is permitted only for local tests.
  Redirects are disabled so Authorization cannot silently follow another endpoint.
- Seed one device first. Once it reports synchronized, configure the other dedicated copies.
  Independently initialized libraries are not automatically merged by notebook title.

## Local-first lifecycle

Successful page/library persistence precedes synchronization. A disconnected device remains
editable, and the next foreground pass discovers durable local changes. Windows activation
and Android resume trigger a check; foreground timers and successful-save notifications request
further passes. Only one pass runs per client. Pausing/closing cancels future network work;
there is no exit-time upload guarantee, background service or requirement for Tailscale.

Content hashes are cached using file identity, modification time and length. New/changed files
are verified while copying immutable staged blobs; the initial scan still reads the whole
library. Same-size edits that deliberately preserve every cache key are outside this shortcut's
detection contract. Mutation barriers invalidate an in-progress local snapshot instead of
publishing an inconsistent library during multi-file navigation/deletion operations.

## Commit, deletion and uncertain requests

The server stores immutable SHA-256 blobs and one versioned manifest. A commit supplies exact
per-path expected versions and an operation ID. All changes succeed together or none do.
Deletion creates a retained tombstone; a stale device cannot resurrect it by assuming absence
means version zero. A blob upload alone never makes a note visible.

Before POST, clients persist an outbox with immutable content and a stable operation ID.
After a lost response or process termination, retry the same request. The server returns its
original immutable receipt, even after later commits; clients acknowledge only affected paths
after validating that receipt. Never discard an uncertain outbox or manufacture a new ID to
turn a rejected request into an overwrite.

## Incoming publication and recovery

Download/verify blobs and persist an incoming journal before changing formal files. Preparation
and large-file copies run on a worker. The foreground idle gate refuses application while input,
unsaved content, page loading, failed saves or navigation mutations are active. Publish assets
and page files before `library.json`, then reload the active repository and preserve local view
location. Final stat/rename work still grows with the number of changed files.

Individual replacements are atomic; an entire local batch is not a filesystem transaction.
An I/O failure or killed process can leave some target files replaced. The journal and immutable
blobs make replay idempotent: an already-matching target is complete, unchanged old targets are
replaced, and unexpected edits are preserved as conflicts. Recover this journal **without
network access, before loading an editable library**. A failure during an existing session also
locks editing until complete local recovery. Do not continue with a partially refreshed model.

Both sides of a conflict and deletion metadata are retained under `.mojian-sync/conflicts`.
Conflicts block synchronization across restart. V1 has no automatic resolution UI or CRDT merge;
preserve the complete clone and recovery directory for reviewed resolution. Deleting checkpoint
or journal files to clear a warning is not a supported recovery procedure.

## Server and verification boundaries

The independent Node service binds loopback. Persistent blobs, manifest, tombstones and
operation receipts belong in a dedicated data root, backed up together. HTTPS ingress,
SakuraFRP/reverse proxy, credentials, service installation and ROG deployment remain separate
operator configuration. No current library or host service is migrated by building the code.

V1 intentionally has no receipt/blob/tombstone garbage collection, multi-user account service,
end-to-end encryption or simultaneous handwriting merge. Monitor storage capacity. Local
protocol tests and build results do not establish public TLS/proxy reachability or physical
Android acceptance; record those layers independently in the delivery log.
