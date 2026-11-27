# macOS read-only synchronization

The user selected automatic receipt of current notes, with the Mac library remaining read-only,
on 2026-11-26. This extends the AppKit client without enabling editing or uploading Mac content.
Windows/Android synchronization, server state, conflicts and identities remain independent.

## Transport and configuration

Use the existing Mojian v1 manifest and immutable blob GET endpoints. The transport API has no
upload/commit operation. Each pass starts at the first configured address and falls back in exact
order for connection/service failures. Validate the expected workspace identity and nondecreasing
revision before accepting content; neither error permits route fallback. Redirects are refused.
Ordinary HTTPS uses system trust; a configured route pin must exactly match the SHA-256 of the
leaf certificate. Pins are request-local and do not install certificates or modify global trust.

Private per-library configuration is stored at
`~/Library/Application Support/Mojian/readonly-sync/<SHA256 of canonical root>.json`, mode 0600.
It contains `endpoint`, ordered `addresses`, `token`, `certificatePins`, `workspaceId`, `enabled`
and `allowInitialReplacement`. No credentials ship in the bundle or repository. Initial replacement
must be explicitly enabled for a known read-only copy. Existing writable/native-sync clones,
Syncthing-managed trees and symlinks are refused.

## Publication and recovery

`.mojian-sync/macos-readonly` owns a separate checkpoint, writer lock, incoming journal and
verified transfer files. It never borrows a Windows/Android device identity or checkpoint.
The first explicitly authorized receive preserves every replaced/deleted local file in
`recovery/<operationId>/<canonical path>`. Later unexpected local changes stop publication,
retaining both the local bytes and prepared server snapshot. Server-only deletions are applied;
unknown local files are never uploaded. Private/recovery files are excluded from discovery.

Downloads stream to disk and are verified with bounded buffers. Every required blob is complete
before journal publication. Preparation copies recovery/replacement files on the worker executor.
A short main-thread publication preflights file identity/size/time, then atomically renames
assets/pages before `library.json` and advances the checkpoint last. The journal retires only
after publication. Cancellation is honored before publication, never between its renames.
An interrupted batch replays without network before constructing a readable repository.
Already matching targets are complete; any other unexpected local hash stops replay.
If an in-session publication/reload fails, clear the bound model until replay/reload succeeds.

Read-only viewing/viewport/preferences never writes canonical note files. After a complete
receive the session reloads its hierarchy and selected page, retaining local viewport and UI
preferences. Open/activation and a foreground timer request one serialized receive pass. An
inactive/closed client cancels networking without deleting the journal or checkpoint.

## Verification

Tests cover GET-only live-service transfer, updates/deletions, duplicate hashes, initial recovery,
unexpected local changes, interrupted publication/offline replay, invalid paths, identity/rollback,
corrupt blobs, ordered fallback and repository reload. Use isolated `/tmp/.agents` libraries for
write-path fixtures. Production acceptance additionally compares actual local file bytes to the
server manifest, opens the signed installed app and confirms its read-only synchronized status.
