# Folder library (canonical format v3)

Read this before changing library loading/saving, page/section/notebook creation, rename, move,
delete, assets or synchronization paths on any client.

## Why

Until v2 the notebook/section hierarchy, titles, order and page appearance lived only in
`library.json`; the disk held `notebooks/<id>/pages/<page-id>.json` and `assets/<page-id>/`.
Nobody asked for that: it was an agent decision from the early Syncthing investigation. The user's
folders were invisible in a file manager, empty sections could not be synchronized, and every
structural edit rewrote one shared file, so two devices editing different sections conflicted on
`library.json`. Reference design: folder-based handwriting/note apps (Saber, GoodNotes export,
Obsidian): the file system is the hierarchy, a page is a file named by its title.

## Layout

```text
<library root>/
  .mojian/assets/<page-id>/<asset-name>      # page assets; never move when a page moves/renames
  <Notebook title>/                          # notebook = top-level folder
    .mojian-folder.json                      # {"version":3,"id":"…","sortKey":"…"}
    <Section title>/                         # section = folder below a notebook, any depth
      .mojian-folder.json
      <Page title>.mojian                    # page = file named by its title
      <Child section>/ …
```

- The folder or file name is the title. There is no second copy of a title.
- A notebook holds sections only; a section holds pages and child sections (unchanged model).
- `.mojian-folder.json` holds the stable id and `sortKey`. A page file is
  `{"version":3,"id","sortKey","page":{createdAt, updatedAt, appearance, PDF fields…},"content":{revision, strokes…}}`.
  `page` (directory metadata) is always written before `content`, so listing a library parses each
  file only up to `content` instead of reading every stroke. Metadata edits rewrite `page`, page saves
  rewrite `content`; neither overwrites the other.
- Order uses fractional `sortKey` strings in each item (Figma/Notion style), not a shared order
  list, so two devices adding or reordering different items never edit the same file.
- Names that the file system or other devices cannot store (`\ / : * ? " < > |`, trailing space or
  dot, reserved Windows names) are replaced by the visually closest full-width characters when a
  title is entered; duplicate titles in one folder get ` (2)`, ` (3)`… as on Windows Explorer.
- `activeNotebookId` and other navigation stay device-local, as before.

## Synchronization

Uses file-sync-core 0.4.0 directory entries (`{hash:null, directory:true}`) like Shale protocol v2:
folders are versioned items, so empty sections, folder rename and delete replicate; a case-only
rename is a tombstone plus a live item in one batch and is applied by renaming, never
delete-then-create. Renaming a section is a folder rename (delete + add of the folder and its
descendants in one batch); the page ids inside stay the same, so open pages and assets follow.
Path policy accepts `.mojian/assets/<page-id>/<name>`, `.mojian-folder.json` and `*.mojian` below
any folder, plus folders. One invalid item is a failed item shown in the UI; others keep syncing.

## Migration (v2 → v3)

No compatibility reader is kept (user rule): the canonical library is migrated once.
`tools/folder-library` converts a v2 tree into v3 (titles → names with the rules above, section
order → sortKey, page metadata into page files, assets → `.mojian/assets`) and verifies page count,
every page's content hash after removing moved metadata, and every asset digest. The live library
is migrated by one commit on the server, then every device upgrades to the v3 client and receives
it through ordinary sync. Order: upgrade the server path policy; confirm every device has
uploaded its pending edits; commit the migration (new files, folder items and tombstones for every
v2 path); upgrade every device immediately. An old client would apply the tombstones and empty
its library, and a v3 client cannot read v2, so the two must not run against one library at once.
The server keeps tombstones indefinitely, so the policy keeps accepting the v2 path shapes
(`library.json`, `notebooks/<id>/pages|assets/…`) as valid names; clients never create them.

### Device side

A device keeps its v2 files until its v3 client starts. Windows and Android then, offline and before
reading the library, move `library.json` and `notebooks/` into `.mojian/legacy-v2/<UTC time>/`
(never deleted). Files whose bytes differ from the last synchronized version are listed in
`unsynced.txt` there and shown on screen: those edits never reached the server. The library is then
empty, so the app shows a receiving screen, runs one sync and opens when the folders arrived; a
failure shows its reason with a retry button. A v2 library without sync state is refused with a
pointer to `tools/folder-library` instead of being moved. v3 clients never plan, upload or receive a
v2 path, so an un-migrated server cannot be damaged by them.

The Mac read-only receiver has no local edits: it receives the migration commit like any update.
Tombstones delete the v2 files (kept in its recovery folder), folders the server does not list are
removed once nothing synchronized remains in them, and the first receive runs before the library is
opened. Read-only opening never writes; folders without an id get one derived from their path.

A page file whose head cannot be read keeps its place through the head of its previous version
(`.mojian/previous`), and loading the page recovers the content from there, on every client.

