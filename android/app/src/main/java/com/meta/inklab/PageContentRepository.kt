package com.meta.inklab

import java.io.File
import org.json.JSONObject

class PageContentRepository(val root: File) {
    private val observed = mutableMapOf<String, String?>()
    private val loadedPages = mutableMapOf<String, java.lang.ref.WeakReference<PageInsertDocument>>()
    private var librarySource: JSONObject? = null
    private var libraryContent: String? = null
    private val receivedPages = mutableSetOf<String>()
    val isShared get() = File(root,"library.json").isFile

    @Synchronized
    fun loadLibrary(): NotebookLibrary {
        val file = File(root,"library.json")
        val text = file.readText(Charsets.UTF_8)
        val source = JSONObject(text)
        val library = SharedWorkspaceCodec.decodeLibrary(source)
        librarySource = source; libraryContent = text
        receivedPages.clear()
        library.notebooks.forEach { notebook -> notebook.allSections().forEach { section ->
            section.pages.forEach { receivedPages += "${notebook.id}/${it.id}" }
        } }
        return library
    }

    @Synchronized
    fun saveLibrary(library: NotebookLibrary) {
        val source = librarySource ?: return
        val next = SharedWorkspaceCodec.encodeLibrary(library,source)
        if (next.toString() == source.toString()) return
        check(File(root,"library.json").readText(Charsets.UTF_8) == libraryContent) { "笔记库已在另一台设备更新，请重新加载后编辑" }
        val text = next.toString()
        atomicWrite(File(root,"library.json"),text)
        librarySource = next; libraryContent = text
    }

    fun libraryChanged(): Boolean = libraryContent?.let { File(root,"library.json").readText(Charsets.UTF_8) != it } ?: false
    fun pageChanged(notebookId: String,pageId: String): Boolean {
        val file = pageFile(notebookId,pageId)
        return observed.containsKey(file.path) && observed[file.path] != stamp(file)
    }

    @Synchronized
    fun load(notebookId: String, pageId: String): PageInsertDocument {
        val file = pageFile(notebookId, pageId)
        check(file.isFile || "$notebookId/$pageId" !in receivedPages) { "页面文件尚未同步完成，请稍后重新加载" }
        val generation=stamp(file)
        loadedPages[file.path]?.get()?.takeIf { it.diskStamp==generation }?.let { return it }
        val source = if(file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else null
        val document = source?.let(SharedWorkspaceCodec::decodeDocument) ?: PageInsertDocument(notebookId,pageId)
        require(document.notebookId == notebookId && document.pageId == pageId) { "Page identity mismatch" }
        document.diskFingerprint = source?.let(WorkspaceJson::fingerprint)
        check(stamp(file)==generation) { "页面正在同步更新，请稍后重新加载" }
        observed[file.path] = generation
        document.diskStamp = generation
        loadedPages[file.path]=java.lang.ref.WeakReference(document)
        return document
    }

    @Synchronized
    fun save(document: PageInsertDocument) {
        val file = pageFile(document.notebookId, document.pageId)
        if (!observed.containsKey(file.path)) {
            check(!file.exists()) { "Load the page before modifying it" }
            observed[file.path] = null
        }
        check(document.diskStamp == stamp(file)) { "页面已在另一台设备更新，已阻止覆盖；请重新加载" }
        val json = SharedWorkspaceCodec.encodeDocument(document)
        if(WorkspaceJson.fingerprint(json)==document.diskFingerprint) return
        json.put("revision",document.sharedSource.optLong("revision")+1).put("updatedAt",java.time.Instant.now().toString())
        atomicWrite(file,json,document.diskStamp)
        document.sharedSource = json
        document.diskFingerprint = WorkspaceJson.fingerprint(json)
        observed[file.path] = stamp(file); document.diskStamp = stamp(file)
        loadedPages[file.path]=java.lang.ref.WeakReference(document)
    }

    private fun stamp(file: File) = if(file.isFile) "${file.lastModified()}:${file.length()}" else null

    private fun atomicWrite(file: File,text: String) = atomicWrite(file) { it.write(text) }
    private fun atomicWrite(file: File,json: JSONObject,expectedStamp: String?) = atomicWrite(file,
        beforeReplace={ check(stamp(file)==expectedStamp) { "页面已在另一台设备更新，已阻止覆盖；请重新加载" } }) { WorkspaceJson.write(json,it) }
    private fun atomicWrite(file: File,beforeReplace: ()->Unit = {},write: (java.io.Writer)->Unit) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Unable to create workspace directory" }
        val temp = File(file.parentFile,"${file.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            java.io.FileOutputStream(temp).use { stream ->
                val writer=java.io.OutputStreamWriter(stream,Charsets.UTF_8)
                write(writer);writer.flush();stream.fd.sync()
            }
            beforeReplace()
            if (file.isFile) file.copyTo(File(file.parentFile,"${file.name}.previous"),overwrite=true)
            java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }

    fun pageFile(notebookId: String, pageId: String): File =
        File(File(File(root, "notebooks"), safeId(notebookId)), "pages/${safeId(pageId)}.json")

    fun assetDirectory(notebookId: String, pageId: String): File =
        File(File(File(root, "notebooks"), safeId(notebookId)), "assets/${safeId(pageId)}").apply { mkdirs() }

    fun notebookDirectory(notebookId: String): File =
        File(File(root, "notebooks"), safeId(notebookId))

    fun deleteNotebookData(notebookId: String) {
        val directory = notebookDirectory(notebookId).canonicalFile
        require(directory.parentFile == File(root, "notebooks").canonicalFile) {
            "Notebook directory must be inside the workspace"
        }
        check(!directory.exists() || directory.deleteRecursively()) { "Unable to delete notebook data" }
    }

    fun deletePageData(notebookId: String, pageId: String) {
        pageFile(notebookId, pageId).delete()
        assetDirectory(notebookId, pageId).deleteRecursively()
    }

    fun copyPageData(
        sourceNotebookId: String,
        sourcePageId: String,
        targetNotebookId: String,
        targetPageId: String,
        extraAssets: List<File> = emptyList(),
    ) {
        val sourceDocument = load(sourceNotebookId, sourcePageId)
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
        val destination = assetDirectory(targetNotebookId, targetPageId)
        val sourceDirectory = assetDirectory(sourceNotebookId, sourcePageId)
        sourceDirectory.listFiles()?.filter(File::isFile)?.forEach { source ->
            source.copyTo(File(destination, source.name), overwrite = true)
        }
        extraAssets.filter(File::isFile).forEach { source ->
            source.copyTo(File(destination, source.name), overwrite = true)
        }
    }

    companion object {
        fun safeId(value: String): String {
            require(value.isNotBlank() && value != "." && value != ".." && value.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid workspace ID" }
            return value
        }
    }
}
