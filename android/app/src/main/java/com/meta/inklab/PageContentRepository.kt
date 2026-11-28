package com.meta.inklab

import java.io.File
import org.json.JSONObject

class PageContentRepository(val root: File, private val hashFile: (File) -> String? = NativeSyncFiles::hash) {
    private val localMutations = LocalMutationEpoch.forWorkspace(root)
    /** The v3 folder library: notebooks/sections are folders, pages `<title>.mojian`, assets `.mojian/assets/<page>`. */
    val folder = FolderLibrary(root)
    private val observed = mutableMapOf<String, String?>()
    private val loadedPages = mutableMapOf<String, java.lang.ref.WeakReference<PageInsertDocument>>()
    private var librarySource: JSONObject? = null
    /** Structure signature (folders, page names, descriptors) when the library was last read or written. */
    @Volatile private var libraryIdentity: String? = null
    private val receivedPages = mutableSetOf<String>()
    // Bytes this process last read or wrote, with every stamp observed for them. HyperOS FUSE
    // later rewrites a replaced file's mtime to the replaced file's old value, so a stamp change
    // is only a hint; the content digest decides whether another writer actually changed it.
    // `physical` (size + inode) of the bytes we read/wrote: Mojian and native sync publish by rename,
    // so while it is unchanged the bytes are ours and a 6.5 MB re-read per save can be skipped.
    private class DiskIdentity(val hash: String, val stamps: MutableSet<String>, val physical: String?)
    private val identities = mutableMapOf<String, DiskIdentity>()
    // Serialized stroke text of the page saved most recently (dense pages are rewritten whole).
    private var strokeTextPath: String? = null
    private var strokeText = StrokeTextCache()
    /** Phase timings of the last completed page save, for on-device diagnosis. */
    @Volatile var lastSaveTimings: String = ""
        private set
    val isShared get() = folder.isLibrary()

    @Synchronized
    fun loadLibrary(): NotebookLibrary {
        val source = folder.read()
        val library = SharedWorkspaceCodec.decodeLibrary(source)
        librarySource = source
        libraryIdentity = folder.signature()
        receivedPages.clear()
        library.notebooks.forEach { notebook -> notebook.allSections().forEach { section ->
            section.pages.forEach { receivedPages += "${notebook.id}/${it.id}" }
        } }
        return library
    }

    /** Synchronized structure only: navigation is device-local and never makes a library "changed". */
    private fun shared(json: JSONObject): String {
        val local = setOf("expanded", "focusedSectionId", "selectedPageSectionId", "selectedPageId", "pagePaneMode", "activeNotebookId")
        fun strip(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().also { result -> value.keys().forEach { key -> if (key !in local) result.put(key, strip(value.get(key))) } }
            is org.json.JSONArray -> org.json.JSONArray().also { result -> for (i in 0 until value.length()) result.put(strip(value.get(i))) }
            else -> value
        }
        return WorkspaceJson.fingerprint(strip(json) as JSONObject)
    }

    fun saveLibrary(library: NotebookLibrary): Boolean {
        val source = librarySource ?: return false
        val next = SharedWorkspaceCodec.encodeLibrary(library, source)
        if (shared(next) == shared(source)) return false
        synchronized(this) {
            check(librarySource === source) { "笔记库已重新加载，请重试操作" }
            check(!libraryChanged()) { "笔记库已在另一台设备更新，请重新加载后编辑" }
            localMutations.begin().use {
                folder.apply(next, beforeWrite = { file ->
                    if (observed.containsKey(file.path)) check(unchanged(file, observed[file.path], verifyContent = true)) {
                        "页面已在另一台设备更新，已阻止覆盖；请重新加载"
                    }
                }, afterWrite = { file, movedFrom ->
                    // This store's own head rewrite or move: the page bytes stay this device's version.
                    movedFrom?.let { from -> observed.remove(from)?.let { observed[file.path] = it }; identities.remove(from)?.let { identities[file.path] = it }
                        loadedPages.remove(from)?.let { loadedPages[file.path] = it } }
                    val stamp = stamp(file)
                    hashFile(file)?.let { hash -> remember(file, hash, physical(file), stamp, observed[file.path]) }
                    if (observed.containsKey(file.path)) observed[file.path] = stamp
                    loadedPages[file.path]?.get()?.diskStamp = stamp
                })
            }
            librarySource = next
            libraryIdentity = folder.signature()
            return true
        }
    }

    fun libraryChanged(): Boolean {
        val known = libraryIdentity ?: return false
        return folder.signature() != known
    }
    @Synchronized
    fun pageChanged(notebookId: String,pageId: String): Boolean {
        val file = pageFile(notebookId,pageId)
        if (!observed.containsKey(file.path)) return false
        if (!unchanged(file, observed[file.path])) return true
        observed[file.path] = stamp(file)
        return false
    }

    /** Current page stamp for callers that later ask [pageUnchangedSince]; not a content identity. */
    fun pageStamp(notebookId: String, pageId: String): String? = stamp(pageFile(notebookId, pageId))

    @Synchronized
    fun pageUnchangedSince(notebookId: String, pageId: String, expectedStamp: String?): Boolean =
        unchanged(pageFile(notebookId, pageId), expectedStamp)

    /**
     * True when [file] still holds the bytes identified by [expectedStamp], even if its metadata drifted.
     * [verifyContent] makes the content digest authoritative even when the stamp matches: HyperOS can
     * report a replaced file with an older mtime, so an equal stamp does not prove equal bytes. Writers
     * verify; frequent UI-side change polls use the stamp as a fast path.
     */
    @Synchronized
    private fun unchanged(file: File, expectedStamp: String?, verifyContent: Boolean = false): Boolean {
        val current = stamp(file)
        if (current == null || expectedStamp == null) return current == expectedStamp
        val known = identities[file.path]?.takeIf { expectedStamp in it.stamps } ?: return current == expectedStamp
        if (current == expectedStamp && !verifyContent) return true
        // Stability during hashing is size + inode: HyperOS may rewrite only the mtime at any moment,
        // which must not fail a check of unchanged bytes; a real replacement always has a new inode.
        val before = physical(file)
        if (before == null || before != known.physical) {
            if (hashFile(file) != known.hash || physical(file) != before) return false
        }
        if (known.stamps.size >= 8) known.stamps.clear()
        known.stamps += expectedStamp; known.stamps += current
        return true
    }

    private fun remember(file: File, hash: String, physical: String?, vararg stamps: String?) {
        identities[file.path] = DiskIdentity(hash, stamps.filterNotNullTo(mutableSetOf()), physical)
    }

    @Synchronized
    fun load(notebookId: String, pageId: String): PageInsertDocument {
        val file = pageFile(notebookId, pageId)
        if (!file.isFile) restoreJson(file) { json -> requireIdentity(contentOf(json, notebookId, pageId), notebookId, pageId) }
        check(file.isFile || "$notebookId/$pageId" !in receivedPages) { "页面文件尚未同步完成，请稍后重新加载" }
        var generation=stamp(file)
        loadedPages[file.path]?.get()?.takeIf { it.diskStamp==generation }?.let { return it }
        removeStaleTemporaries(file)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val source = if (file.isFile) try {
            contentOf(readJson(file, digest), notebookId, pageId).also { SharedWorkspaceCodec.decodeDocument(it) }
        } catch (error: Exception) {
            restoreJson(file) { json -> requireIdentity(contentOf(json, notebookId, pageId), notebookId, pageId) }
            digest.reset()
            generation = stamp(file)
            contentOf(readJson(file, digest), notebookId, pageId)
        } else null
        val readHash = source?.let { hex(digest.digest()) }
        val document = source?.let(SharedWorkspaceCodec::decodeDocument) ?: PageInsertDocument(notebookId,pageId)
        require(document.notebookId == notebookId && document.pageId == pageId) { "Page identity mismatch" }
        document.diskFingerprint = source?.let(WorkspaceJson::fingerprint)
        val after = stamp(file)
        val afterPhysical = physical(file)
        check(after==generation || (readHash != null && after != null && hashFile(file) == readHash && physical(file) == afterPhysical)) {
            "页面正在同步更新，请稍后重新加载"
        }
        if (readHash != null) remember(file, readHash, afterPhysical, generation, after) else identities.remove(file.path)
        observed[file.path] = generation
        document.diskStamp = generation
        loadedPages[file.path]=java.lang.ref.WeakReference(document)
        return document
    }

    /** A folder-library page's editable part; staged content of a page not yet in the library is already bare. */
    private fun contentOf(file: JSONObject, notebookId: String, pageId: String): JSONObject {
        val content = if (file.optInt("version") == 3 && file.has("content")) file.getJSONObject("content") else file
        return content.put("notebookId", notebookId).put("pageId", pageId).also { if (!it.has("version")) it.put("version", 2) }
    }

    private fun requireIdentity(content: JSONObject, notebookId: String, pageId: String) {
        val restored = SharedWorkspaceCodec.decodeDocument(content)
        require(restored.notebookId == notebookId && restored.pageId == pageId) { "Page identity mismatch" }
    }

    @Synchronized
    fun save(document: PageInsertDocument) {
        val file = pageFile(document.notebookId, document.pageId)
        if (!observed.containsKey(file.path)) {
            check(!file.exists()) { "Load the page before modifying it" }
            observed[file.path] = null
        }
        val started = System.nanoTime()
        check(unchanged(file, document.diskStamp, verifyContent = true)) { "页面已在另一台设备更新，已阻止覆盖；请重新加载" }
        val verified = System.nanoTime()
        // Content was verified above; continue from the stamp the disk reports now. The pre-replace
        // check below only guards the short encode/write window, so its stamp fast path is enough.
        val expectedStamp = stamp(file)
        document.diskStamp = expectedStamp
        val json = SharedWorkspaceCodec.encodeDocument(document)
        if(WorkspaceJson.fingerprint(json)==document.diskFingerprint) return
        json.put("revision",document.sharedSource.optLong("revision")+1).put("updatedAt",java.time.Instant.now().toString())
        val encoded = System.nanoTime()
        if (strokeTextPath != file.path) { strokeTextPath = file.path; strokeText = StrokeTextCache() }
        // A library page keeps its stored head; content staged before the page joins the library is written bare.
        val pageId = document.pageId
        val output = folder.index.pages[pageId]?.let {
            PageFileFormat.compose(pageId, folder.index.keys[pageId].orEmpty(), JSONObject(folder.index.pageMeta[pageId] ?: "{}"), json)
        } ?: json
        val writtenHash = atomicWrite(file,output,expectedStamp,strokeText)
        val written = System.nanoTime()
        document.sharedSource = json
        document.diskFingerprint = WorkspaceJson.fingerprint(json)
        fun ms(from: Long, to: Long) = (to - from) / 1_000_000
        lastSaveTimings = "verify ${ms(started, verified)} ms, encode ${ms(verified, encoded)} ms, " +
            "write ${ms(encoded, written)} ms (strokes cached ${strokeText.hits}/${strokeText.hits + strokeText.misses}), " +
            "fingerprint ${ms(written, System.nanoTime())} ms, ${file.length()} bytes"
        val savedStamp = stamp(file)
        observed[file.path] = savedStamp; document.diskStamp = savedStamp
        remember(file, writtenHash, physical(file), savedStamp)
        loadedPages[file.path]=java.lang.ref.WeakReference(document)
    }

    private fun stamp(file: File) = if(file.isFile) "${file.lastModified()}:${file.length()}" else null
    /**
     * A write killed mid-way (crash, swipe-away, background freeze then kill) leaves `<page>.json.<uuid>.tmp`.
     * The published page is intact (rename is atomic); remove such leftovers once they are clearly
     * abandoned. An in-flight write of this process is always younger than the threshold.
     */
    private fun removeStaleTemporaries(file: File, now: Long = System.currentTimeMillis()) {
        val prefix = "${file.name}."
        folder.temporaryFile(file).parentFile?.listFiles { candidate ->
            candidate.isFile && candidate.name.startsWith(prefix) && candidate.name.endsWith(".tmp") &&
                now - candidate.lastModified() > STALE_TEMPORARY_MS
        }?.forEach { it.delete() }
    }

    /** Size and inode only; unlike [stamp] it is not affected by HyperOS mtime rewrites. */
    private fun physical(file: File): String? = runCatching {
        java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS).let { attributes ->
            // Without an inode key, size alone is no identity: callers then fall back to hashing.
            attributes.fileKey()?.let { "${attributes.size()}:$it" }
        }
    }.getOrNull()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun atomicWrite(file: File,json: JSONObject,expectedStamp: String?,cache: StrokeTextCache?) = atomicWrite(file,
        beforeReplace={ check(unchanged(file, expectedStamp)) { "页面已在另一台设备更新，已阻止覆盖；请重新加载" } }) { WorkspaceJson.write(json,it,cache) }
    /** Returns the SHA-256 of the bytes published at [file]. */
    private fun atomicWrite(file: File,beforeReplace: ()->Unit = {},rotatePrevious: Boolean = true,write: (java.io.Writer)->Unit): String {
        localMutations.begin().use {
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Unable to create workspace directory" }
            val temp = folder.temporaryFile(file).also { it.parentFile!!.mkdirs() }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            try {
                java.io.FileOutputStream(temp).use { stream ->
                    // Few large writes: FUSE shared storage is slow per syscall.
                    val writer=java.io.BufferedWriter(java.io.OutputStreamWriter(java.security.DigestOutputStream(
                        java.io.BufferedOutputStream(stream, 256 * 1024), digest),Charsets.UTF_8), 64 * 1024)
                    write(writer);writer.flush();stream.fd.sync()
                }
                beforeReplace()
                if (rotatePrevious && file.isFile) file.copyTo(folder.previousFile(file).also { it.parentFile!!.mkdirs() },overwrite=true)
                java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            return hex(digest.digest())
        }
    }

    /** Streams a page into org.json; dense pages must never be materialized as one String first. */
    private fun readJson(file: File, digest: java.security.MessageDigest? = null): JSONObject {
        val bytes = java.io.BufferedInputStream(file.inputStream(), 256 * 1024)
        val input = if (digest == null) bytes else java.security.DigestInputStream(bytes, digest)
        return input.reader(Charsets.UTF_8).use(StreamingJson::readObject)
    }

    private fun restoreJson(file: File, validate: (JSONObject) -> Unit) {
        fun read(source: File): JSONObject = readJson(source).also(validate)
        if (file.isFile) {
            try { read(file); return }
            catch (error: Exception) {
                val previous = folder.previousFile(file)
                val restored = try { read(previous) } catch (_: Exception) { throw error }
                val corrupt = File(root, ".mojian-corrupt.previous/${file.relativeTo(root).path}.${java.util.UUID.randomUUID()}")
                corrupt.parentFile!!.mkdirs()
                file.copyTo(corrupt, overwrite = false)
                atomicWrite(file, rotatePrevious = false) { it.write(restored.toString()) }
            }
        } else {
            val previous = folder.previousFile(file)
            if (previous.isFile) {
                val restored = read(previous)
                atomicWrite(file, rotatePrevious = false) { it.write(restored.toString()) }
            }
        }
    }

    /** Publish asset bytes only after the complete stream is durable. */
    fun writeAsset(notebookId: String, pageId: String, name: String, write: (java.io.OutputStream) -> Unit): File {
        localMutations.begin().use {
            val target = File(assetDirectory(notebookId, pageId), safeId(name))
            check(!target.exists()) { "Asset already exists" }
            val temporary = File(target.path + ".${java.util.UUID.randomUUID()}.tmp")
            try {
                java.io.FileOutputStream(temporary).use { stream ->
                    val buffered = java.io.BufferedOutputStream(stream, 256 * 1024)
                    write(buffered); buffered.flush(); stream.fd.sync()
                }
                java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { temporary.delete() }
            return target
        }
    }

    /** The page's file in the folder library, or its staged content before the page joins the library. */
    fun pageFile(notebookId: String, pageId: String): File = folder.pagePath(pageId) ?: folder.pendingFile(pageId)

    fun assetDirectory(notebookId: String, pageId: String): File = folder.assetDirectory(pageId).apply { mkdirs() }

    fun notebookDirectory(notebookId: String): File = folder.index.folders[notebookId]?.let { File(root, it) } ?: File(root, ".mojian/missing/$notebookId")

    /** Folder deletions happen when the library is saved; this removes content of pages that never joined it. */
    @Synchronized
    fun deleteNotebookData(notebookId: String) {
        localMutations.begin().use {
            check(!isShared || !libraryChanged()) { "笔记库已更新，删除清理待重新加载后重试" }
        }
    }

    @Synchronized
    fun deletePageData(notebookId: String, pageId: String) {
        localMutations.begin().use {
            check(!isShared || !libraryChanged()) { "笔记库已更新，删除清理待重新加载后重试" }
            if (folder.pagePath(pageId) != null) return
            if (librarySource?.let { source -> SharedWorkspaceCodec.decodeLibrary(source).notebooks.flatMap { it.allSections() }
                    .flatMap { it.pages }.any { it.pdfPage?.sourceOwnerPageId == pageId } } == true) return
            val page = folder.pendingFile(pageId)
            check(!page.exists() || page.delete()) { "Unable to delete page data" }
            observed.remove(page.path); identities.remove(page.path); loadedPages.remove(page.path)
            check(folder.assetDirectory(pageId).deleteRecursively()) { "Unable to delete page assets" }
        }
    }

    fun copyPageData(
        sourceNotebookId: String,
        sourcePageId: String,
        targetNotebookId: String,
        targetPageId: String,
        extraAssets: List<File> = emptyList(),
    ) {
        localMutations.begin().use {
            val sourceDocument = load(sourceNotebookId, sourcePageId)
            check(!pageFile(targetNotebookId, targetPageId).exists()) { "Target page already exists" }
            val sourceDirectory = assetDirectory(sourceNotebookId, sourcePageId)
            val assets = (sourceDirectory.listFiles()?.filter(File::isFile).orEmpty() + extraAssets)
                .distinctBy { it.canonicalFile }
            assets.forEach { source -> check(source.isFile) { "Source asset has not synchronized: ${source.name}" } }
            val required = sourceDocument.objects.flatMap { item -> when(item) {
                is InsertObject.Attachment -> listOf(item.assetName)
                is InsertObject.Spreadsheet -> listOf(item.assetName)
                is InsertObject.Image -> listOfNotNull(item.assetName, item.sourceAssetName)
                else -> emptyList()
            } }.toSet()
            val available = assets.map { it.name }.toSet()
            required.forEach { name ->
                safeId(name)
                check(name in available) { "Source asset has not synchronized: $name" }
            }
            val destination = assetDirectory(targetNotebookId, targetPageId)
            assets.forEach { source -> source.copyTo(File(destination, source.name), overwrite = false) }
            if (sourceDocument.objects.isNotEmpty() || pageFile(sourceNotebookId, sourcePageId).isFile) {
                save(
                    PageInsertDocument(
                        notebookId = targetNotebookId,
                        pageId = targetPageId,
                        objects = sourceDocument.objects.mapTo(mutableListOf()) { InsertObject.fromJson(it.toJson()) },
                        strokes = org.json.JSONArray(sourceDocument.strokes.toString()),
                        sharedSource = SharedWorkspaceCodec.copy(sourceDocument.sharedSource),
                    ),
                )
            }
        }
    }

    companion object {
        internal const val STALE_TEMPORARY_MS = 60 * 60 * 1000L

        fun safeId(value: String): String {
            // Same component rule as native sync and Windows WorkspaceStore.SafeId.
            require(value.isNotEmpty() && value.length < 240 && value.first() != '.' && value.last() != '.' &&
                value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._-" }) { "Invalid workspace ID" }
            return value
        }
    }
}
