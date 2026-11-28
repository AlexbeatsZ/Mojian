package com.meta.inklab

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Names of the v3 folder library (docs/design/folder-library.md); identical rules to
 * tools/folder-library/convert.mjs and the Windows FolderNames, so a title maps to one file name everywhere.
 */
object FolderNames {
    const val PAGE_EXTENSION = ".mojian"
    const val DESCRIPTOR = ".mojian-folder.json"
    const val HIDDEN_ROOT = ".mojian"
    private val fullWidth = mapOf('\\' to '＼', '/' to '／', ':' to '：', '*' to '＊', '?' to '？',
        '"' to '＂', '<' to '＜', '>' to '＞', '|' to '｜')
    private val reserved = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?$", RegexOption.IGNORE_CASE)

    fun storable(title: String?): String {
        val builder = StringBuilder()
        var index = 0
        val text = title.orEmpty()
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (codePoint < 0x20 || codePoint == 0x7f) continue
            val mapped = if (codePoint < 0x10000) fullWidth[codePoint.toChar()] else null
            if (mapped != null) builder.append(mapped) else builder.appendCodePoint(codePoint)
        }
        var name = builder.toString().trim()
        if (name.startsWith('.')) name = "．" + name.substring(1)
        if (name.endsWith('.')) name = name.dropLast(1) + "．"
        if (name.isEmpty()) name = "无标题"
        if (reserved.matches(name)) name += "_"
        val count = name.codePointCount(0, name.length)
        return if (count > 120) name.substring(0, name.offsetByCodePoints(0, 120)).trim() else name
    }

    /** Explorer-style de-duplication, case-insensitive across pages and folders of one parent. */
    fun unique(base: String, suffix: String, taken: MutableSet<String>): String {
        var n = 1
        while (true) {
            val candidate = (if (n == 1) base else "$base ($n)") + suffix
            if (taken.add(candidate.lowercase())) return candidate
            n++
        }
    }

    /** True when [name] already stores [title], possibly with a de-dup suffix. */
    fun represents(name: String, title: String?, suffix: String = ""): Boolean {
        if (!name.endsWith(suffix)) return false
        val stem = name.dropLast(suffix.length)
        val storable = storable(title)
        if (stem == storable) return true
        val match = Regex("^(.*) \\((\\d+)\\)$").matchEntire(stem) ?: return false
        return match.groupValues[1] == storable && match.groupValues[2].toInt() >= 2
    }
}

/** Fractional ordering keys, a port of rocicorp/fractional-indexing (CC0-1.0); compared ordinally. */
object FractionalIndex {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val ZERO = '0'

    private fun midpoint(a: String, b: String?): String {
        require(b == null || a < b) { "$a >= $b" }
        require(a.lastOrNull() != ZERO && b?.lastOrNull() != ZERO) { "trailing zero" }
        if (!b.isNullOrEmpty()) {
            var n = 0
            while ((a.getOrNull(n) ?: ZERO) == b[n]) n++
            if (n > 0) return b.substring(0, n) + midpoint(if (a.length > n) a.substring(n) else "", b.substring(n))
        }
        val digitA = if (a.isNotEmpty()) DIGITS.indexOf(a[0]) else 0
        val digitB = if (b != null) DIGITS.indexOf(b[0]) else DIGITS.length
        if (digitB - digitA > 1) return DIGITS[Math.round(0.5 * (digitA + digitB)).toInt()].toString()
        if (b != null && b.length > 1) return b.substring(0, 1)
        return DIGITS[digitA] + midpoint(if (a.length > 1) a.substring(1) else "", null)
    }

    private fun integerLength(head: Char) = when (head) {
        in 'a'..'z' -> head - 'a' + 2
        in 'A'..'Z' -> 'Z' - head + 2
        else -> throw IllegalArgumentException("invalid order key head: $head")
    }

    private fun integerPart(key: String): String {
        val length = integerLength(key[0])
        require(length <= key.length) { "invalid order key: $key" }
        return key.substring(0, length)
    }

    fun isValid(key: String?): Boolean {
        if (key.isNullOrEmpty() || key == "A" + ZERO.toString().repeat(26)) return false
        return runCatching { val integer = integerPart(key); key.length == integer.length || key.last() != ZERO }.getOrDefault(false)
    }

    private fun increment(x: String): String? {
        val head = x[0]
        val digits = x.substring(1).toMutableList()
        var carry = true
        var i = digits.size - 1
        while (carry && i >= 0) {
            val d = DIGITS.indexOf(digits[i]) + 1
            if (d == DIGITS.length) digits[i] = ZERO else { digits[i] = DIGITS[d]; carry = false }
            i--
        }
        if (!carry) return head + digits.joinToString("")
        if (head == 'Z') return "a$ZERO"
        if (head == 'z') return null
        val next = head + 1
        if (next > 'a') digits.add(ZERO) else digits.removeAt(digits.size - 1)
        return next + digits.joinToString("")
    }

    private fun decrement(x: String): String? {
        val head = x[0]
        val digits = x.substring(1).toMutableList()
        var borrow = true
        var i = digits.size - 1
        while (borrow && i >= 0) {
            val d = DIGITS.indexOf(digits[i]) - 1
            if (d == -1) digits[i] = DIGITS.last() else { digits[i] = DIGITS[d]; borrow = false }
            i--
        }
        if (!borrow) return head + digits.joinToString("")
        if (head == 'a') return "Z" + DIGITS.last()
        if (head == 'A') return null
        val next = head - 1
        if (next < 'Z') digits.add(DIGITS.last()) else digits.removeAt(digits.size - 1)
        return next + digits.joinToString("")
    }

    /** A key strictly between [a] and [b]; null is unbounded. */
    fun between(a: String?, b: String?): String {
        require(a == null || isValid(a)) { "invalid order key: $a" }
        require(b == null || isValid(b)) { "invalid order key: $b" }
        require(a == null || b == null || a < b) { "$a >= $b" }
        if (a == null) {
            if (b == null) return "a$ZERO"
            val ib = integerPart(b)
            if (ib == "A" + ZERO.toString().repeat(26)) return ib + midpoint("", b.substring(ib.length))
            if (ib < b) return ib
            return decrement(ib) ?: error("cannot decrement any more")
        }
        if (b == null) {
            val ia = integerPart(a)
            return increment(ia) ?: (ia + midpoint(a.substring(ia.length), null))
        }
        val ia = integerPart(a)
        val ib = integerPart(b)
        if (ia == ib) return ia + midpoint(a.substring(ia.length), b.substring(ib.length))
        val next = increment(ia) ?: error("cannot increment any more")
        return if (next < b) next else ia + midpoint(a.substring(ia.length), null)
    }

    /** Keys for [ordered] in display order, keeping every still-increasing existing key so a move rewrites only moved items. */
    fun assign(ordered: List<String>, existing: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var previous: String? = null
        ordered.forEachIndexed { i, id ->
            val key = existing[id]
            if (isValid(key) && (previous == null || key!! > previous!!)) { previous = key; result[id] = key!!; return@forEachIndexed }
            var next: String? = null
            for (j in i + 1 until ordered.size) {
                val later = existing[ordered[j]]
                if (isValid(later) && (previous == null || later!! > previous!!)) { next = later; break }
            }
            val assigned = between(previous, next)
            previous = assigned
            result[id] = assigned
        }
        return result
    }
}

/** `{"version":3,"id","sortKey","page":{…},"content":{…}}`; `page` is always written before `content`. */
object PageFileFormat {
    data class Head(val version: Int, val id: String?, val sortKey: String?, val page: JSONObject?)

    fun readHead(file: File): Head = file.bufferedReader(Charsets.UTF_8).use { reader ->
        val head = StreamingJson.readUntil(reader, "content")
        Head(head.optInt("version"), head.optString("id").ifEmpty { null }, head.optString("sortKey").ifEmpty { null }, head.optJSONObject("page"))
    }

    fun compose(id: String, sortKey: String, page: JSONObject, content: JSONObject): JSONObject =
        JSONObject().put("version", 3).put("id", id).put("sortKey", sortKey).put("page", page).put("content", content)
}

/**
 * The v3 folder library as the canonical v2-shaped library JSON the Android codec already understands. Reading walks the
 * folder tree (adopting folders and pages made in a file manager); applying turns a changed library into folder and
 * file operations. Navigation fields never reach a synchronized descriptor; Android keeps them in its preferences.
 */
class FolderLibrary(val root: File) {
    class Index {
        val folders = mutableMapOf<String, String>()      // notebook/section id -> relative folder
        val pages = mutableMapOf<String, String>()        // page id -> relative file
        val keys = mutableMapOf<String, String>()         // item id -> sortKey
        val pageMeta = mutableMapOf<String, String>()     // page id -> "page" object text as stored
        val folderMeta = mutableMapOf<String, String>()   // folder id -> descriptor extras text
        val pageOwner = mutableMapOf<String, String>()    // page id -> notebook id
        internal val pageObjects = mutableMapOf<String, JSONObject>()
        internal val duplicates = mutableListOf<Pair<String, String>>()
    }

    var index = Index()
        private set
    private var indexed = false

    private fun absolute(relative: String) = File(root, relative)
    private fun combine(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
    val pendingDirectory get() = File(root, "${FolderNames.HIDDEN_ROOT}/pending")
    fun pendingFile(pageId: String) = File(pendingDirectory, "${PageContentRepository.safeId(pageId)}.json")
    fun assetDirectory(pageId: String) = File(root, "${FolderNames.HIDDEN_ROOT}/assets/${PageContentRepository.safeId(pageId)}")

    fun pagePath(pageId: String): File? { ensureIndex(); return index.pages[pageId]?.let(::absolute) }

    fun ensureIndex() {
        if (indexed) return
        read()
    }

    /** True when [root] holds a folder library (or is a fresh native clone of one). */
    fun isLibrary(): Boolean = File(root, FolderNames.HIDDEN_ROOT).isDirectory ||
        root.listFiles()?.any { it.isDirectory && !it.name.startsWith('.') && File(it, FolderNames.DESCRIPTOR).isFile } == true

    /** The canonical library: `{"version":2,"notebooks":[…]}` with pages carrying their head metadata. */
    @Synchronized
    fun read(): JSONObject {
        val next = Index()
        val notebooks = readFolders("", next, null)
        resolveDuplicates(next, index)
        index = next
        indexed = true
        return JSONObject().put("version", 2).put("notebooks", JSONArray().apply {
            notebooks.forEach { put(notebookJson(it)) }
        })
    }

    private class FolderRead(val id: String, val name: String, val relative: String, val extras: JSONObject,
        val folders: List<FolderRead>, val pages: List<JSONObject>)

    private fun notebookJson(folder: FolderRead): JSONObject = copyInto(JSONObject(), folder.extras)
        .put("id", folder.id).put("title", folder.name).put("sections", JSONArray().apply { folder.folders.forEach { put(sectionJson(it)) } })

    private fun sectionJson(folder: FolderRead): JSONObject = copyInto(JSONObject(), folder.extras)
        .put("id", folder.id).put("title", folder.name)
        .put("children", JSONArray().apply { folder.folders.forEach { put(sectionJson(it)) } })
        .put("pages", JSONArray().apply { folder.pages.forEach(::put) })

    private fun copyInto(target: JSONObject, source: JSONObject): JSONObject {
        source.keys().forEach { target.put(it, source.get(it)) }
        return target
    }

    private fun readDescriptor(file: File): JSONObject {
        fun parse(source: File) = runCatching { JSONObject(source.readText(Charsets.UTF_8)) }.getOrNull()
        if (!file.isFile) return JSONObject()
        parse(file)?.let { return it }
        val corrupt = File(root, ".mojian-corrupt.previous/${file.relativeTo(root).path}.${java.util.UUID.randomUUID()}")
        corrupt.parentFile!!.mkdirs(); file.copyTo(corrupt)
        val previous = previousFile(file)
        parse(previous)?.let { previous.copyTo(file, overwrite = true); return it }
        return JSONObject()
    }

    private fun readFolders(relative: String, index: Index, notebookId: String?): List<FolderRead> {
        val directory = if (relative.isEmpty()) root else absolute(relative)
        val result = mutableListOf<FolderRead>()
        directory.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            ?.sortedBy { it.name }?.forEach { folder ->
                val childRelative = combine(relative, folder.name)
                val descriptorFile = File(folder, FolderNames.DESCRIPTOR)
                val descriptor = readDescriptor(descriptorFile)
                var id = descriptor.optString("id")
                if (id.isEmpty() || id in index.folders || !safe(id)) {
                    // A folder made in a file manager, or a copied descriptor, becomes its own notebook/section.
                    id = newId(if (notebookId == null) "notebook" else "section")
                    descriptor.put("id", id).put("version", 3)
                    atomicText(descriptorFile, descriptor.toString(2))
                }
                index.folders[id] = childRelative
                val extras = JSONObject(descriptor.toString()).apply { remove("version"); remove("id"); remove("sortKey") }
                index.folderMeta[id] = extras.toString()
                val key = descriptor.optString("sortKey").takeIf { FractionalIndex.isValid(it) }.orEmpty()
                val owner = notebookId ?: id
                result += FolderRead(id, folder.name, childRelative, extras, readFolders(childRelative, index, owner),
                    if (notebookId == null) emptyList() else readPages(childRelative, index, owner)).also { index.keys[id] = key }
            }
        assignMissingKeys(result.map { Triple(it.id, index.keys[it.id].orEmpty(), it.name) }, index)
        return result.sortedWith(compareBy<FolderRead> { index.keys[it.id] }.thenBy { it.name })
    }

    private fun readPages(relative: String, index: Index, notebookId: String): List<JSONObject> {
        val pages = mutableListOf<JSONObject>()
        absolute(relative).listFiles()?.filter { it.isFile && it.name.endsWith(FolderNames.PAGE_EXTENSION) && !it.name.startsWith('.') }
            ?.sortedBy { it.name }?.forEach { file ->
                // A corrupt page keeps its place through the previous version's head; loading it recovers the content.
                val head = runCatching { PageFileFormat.readHead(file) }.getOrNull()
                    ?: runCatching { PageFileFormat.readHead(previousFile(file)) }.getOrNull() ?: return@forEach
                val relativeFile = combine(relative, file.name)
                var id = head.id.orEmpty()
                if (id.isNotEmpty() && safe(id) && id in index.pages) {
                    index.duplicates += relativeFile to id
                    id = newId("page")
                } else if (id.isEmpty() || !safe(id)) {
                    id = newId("page")
                    rewriteHead(file, id, head.sortKey.orEmpty(), head.page ?: JSONObject())
                }
                val meta = head.page ?: JSONObject()
                val page = copyInto(JSONObject(), meta).put("id", id).put("title", file.name.dropLast(FolderNames.PAGE_EXTENSION.length))
                index.pages[id] = relativeFile
                index.pageOwner[id] = notebookId
                index.pageMeta[id] = meta.toString()
                index.keys[id] = head.sortKey?.takeIf { FractionalIndex.isValid(it) }.orEmpty()
                index.pageObjects[relativeFile] = page
                pages += page
            }
        assignMissingKeys(pages.map { Triple(it.getString("id"), index.keys[it.getString("id")].orEmpty(), it.getString("title")) }, index)
        return pages.sortedWith(compareBy<JSONObject> { index.keys[it.getString("id")] }.thenBy { it.getString("title") })
    }

    private fun assignMissingKeys(items: List<Triple<String, String, String>>, index: Index) {
        var last = items.map { it.second }.filter { it.isNotEmpty() }.maxOrNull()
        items.sortedBy { it.third }.forEach { (id, key, _) -> index.keys[id] = key.ifEmpty { FractionalIndex.between(last, null).also { last = it } } }
    }

    /** The file the previous index knew keeps a duplicated id (otherwise the older file); the copy gets a new id. */
    private fun resolveDuplicates(index: Index, previous: Index) {
        for ((copyPath, originalId) in index.duplicates) {
            val firstPath = index.pages.getValue(originalId)
            val copyPage = index.pageObjects.getValue(copyPath)
            val copyId = copyPage.getString("id")
            val copyWins = previous.pages[originalId]?.let { it == copyPath }
                ?: (created(absolute(copyPath)) < created(absolute(firstPath)))
            val loserPath = if (copyWins) firstPath else copyPath
            if (copyWins) {
                index.pageObjects.getValue(firstPath).put("id", copyId); copyPage.put("id", originalId)
                index.pages[originalId] = copyPath; index.pages[copyId] = firstPath
                for (map in listOf(index.pageMeta, index.keys, index.pageOwner)) {
                    val original = map.remove(originalId); val other = map.remove(copyId)
                    if (other != null) map[originalId] = other
                    if (original != null) map[copyId] = original
                }
            }
            val loser = absolute(loserPath)
            val head = PageFileFormat.readHead(loser)
            rewriteHead(loser, copyId, head.sortKey.orEmpty(), head.page ?: JSONObject())
        }
        index.duplicates.clear()
    }

    private fun created(file: File): Long = runCatching {
        java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).creationTime().toMillis()
    }.getOrDefault(file.lastModified())

    /** Folder names, page files and descriptor stamps; page saves change only content and never look structural. */
    fun signature(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun walk(directory: File, relative: String) {
            directory.listFiles()?.sortedBy { it.name }?.forEach { entry ->
                if (entry.name.startsWith('.') && entry.name != FolderNames.DESCRIPTOR) return@forEach
                val name = combine(relative, entry.name)
                when {
                    entry.isDirectory -> { digest.update("D $name\n".toByteArray()); walk(entry, name) }
                    entry.name == FolderNames.DESCRIPTOR -> digest.update("F $name ${entry.length()}:${entry.lastModified()}\n".toByteArray())
                    entry.name.endsWith(FolderNames.PAGE_EXTENSION) -> digest.update("P $name\n".toByteArray())
                }
            }
        }
        walk(root, "")
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    // ---- Applying a changed library ------------------------------------------------------------------------------

    private class Placement(val id: String, val title: String, val isPage: Boolean, val parent: String?, val owner: String, val json: JSONObject)

    /** Turns [library] (canonical JSON) into folder operations; [writeContent] supplies new page content. */
    @Synchronized
    fun apply(library: JSONObject, beforeWrite: (File) -> Unit = {}, afterWrite: (File, String?) -> Unit = { _, _ -> }) {
        ensureIndex()
        val placements = mutableListOf<Placement>()
        fun addSection(section: JSONObject, parent: String, owner: String) {
            placements += Placement(section.getString("id"), section.getString("title"), false, parent, owner, section)
            SharedWorkspaceCodec.items(section.optJSONArray("pages")).forEach { page ->
                placements += Placement(page.getString("id"), page.getString("title"), true, section.getString("id"), owner, page)
            }
            SharedWorkspaceCodec.items(section.optJSONArray("children")).forEach { addSection(it, section.getString("id"), owner) }
        }
        SharedWorkspaceCodec.items(library.getJSONArray("notebooks")).forEach { notebook ->
            val id = notebook.getString("id")
            placements += Placement(id, notebook.getString("title"), false, null, id, notebook)
            SharedWorkspaceCodec.items(notebook.optJSONArray("sections")).forEach { addSection(it, id, id) }
        }
        val wanted = placements.map { it.id }.toSet()

        for ((id, relative) in index.pages.filterKeys { it !in wanted }) {
            val file = absolute(relative)
            if (file.isFile) { beforeWrite(file); file.delete() }
            assetDirectory(id).deleteRecursively()
            index.pages.remove(id); index.pageMeta.remove(id); index.keys.remove(id); index.pageOwner.remove(id)
        }
        // A removed folder is only renamed aside here: pages and sections moved out of it (a merge, a move before a
        // delete) still live inside it. What remains is deleted once every kept item is in place.
        val removedFolders = mutableListOf<File>()
        for ((id, relative) in index.folders.filterKeys { it !in wanted }.entries.sortedByDescending { it.value.length }) {
            index.folders.remove(id); index.folderMeta.remove(id); index.keys.remove(id)
            val folder = absolute(relative)
            if (!folder.isDirectory) continue
            val aside = File(folder.parentFile, ".mojian-remove-${java.util.UUID.randomUUID()}")
            check(folder.renameTo(aside)) { "无法移除 ${folder.name}" }
            moveIndex(relative, aside.relativeTo(root).invariantSeparatorsPath, false)
            removedFolders += aside
        }

        val keys = mutableMapOf<String, String>()
        placements.groupBy { it.parent to it.isPage }.values.forEach { siblings ->
            keys += FractionalIndex.assign(siblings.map { it.id }, index.keys)
        }
        fun currentRelative(item: Placement) = if (item.isPage) index.pages[item.id] else index.folders[item.id]
        val names = mutableMapOf<String, String>()
        placements.groupBy { it.parent }.forEach { (parent, items) ->
            val parentRelative = if (parent == null) "" else index.folders[parent]
            val taken = mutableSetOf(FolderNames.DESCRIPTOR.lowercase())
            val decided = items.map { item ->
                val suffix = if (item.isPage) FolderNames.PAGE_EXTENSION else ""
                val current = currentRelative(item)
                val keep = current != null && current.substringBeforeLast('/', "") == parentRelative &&
                    FolderNames.represents(current.substringAfterLast('/'), item.title, suffix) && taken.add(current.substringAfterLast('/').lowercase())
                item to (if (keep) current!!.substringAfterLast('/') else null)
            }
            decided.forEach { (item, name) ->
                names[item.id] = name ?: FolderNames.unique(FolderNames.storable(item.title), if (item.isPage) FolderNames.PAGE_EXTENSION else "", taken)
            }
        }
        val byId = placements.associateBy { it.id }
        fun finalRelative(item: Placement): String = if (item.parent == null) names.getValue(item.id)
            else combine(finalRelative(byId.getValue(item.parent)), names.getValue(item.id))

        // Two-phase moves through hidden temporary names: swaps and moves into renamed parents never collide.
        val staged = mutableSetOf<String>()
        placements.filter { currentRelative(it)?.let { current -> current != finalRelative(it) } == true }
            .sortedByDescending { currentRelative(it)!!.length }.forEach { item ->
                val current = absolute(currentRelative(item)!!)
                val temporary = File(current.parentFile, ".mojian-move-${java.util.UUID.randomUUID()}")
                if (item.isPage) beforeWrite(current)
                check(current.renameTo(temporary)) { "无法移动 ${current.name}" }
                moveIndex(currentRelative(item)!!, temporary.relativeTo(root).invariantSeparatorsPath, item.isPage)
                staged += item.id
            }
        for (item in placements) {
            val target = absolute(finalRelative(item))
            if (!item.isPage) {
                if (item.id in staged) check(absolute(index.folders.getValue(item.id)).renameTo(target)) { "无法移动 ${target.name}" }
                else if (!target.isDirectory) check(target.mkdirs()) { "无法创建 ${target.name}" }
                moveIndex(index.folders[item.id] ?: finalRelative(item), finalRelative(item), false)
                index.folders[item.id] = finalRelative(item)
                val extras = descriptorExtras(item.json)
                val descriptor = File(target, FolderNames.DESCRIPTOR)
                if (!descriptor.isFile || index.keys[item.id] != keys[item.id] || index.folderMeta[item.id] != extras.toString()) {
                    val json = JSONObject().put("version", 3).put("id", item.id).put("sortKey", keys.getValue(item.id))
                    copyInto(json, extras)
                    atomicText(descriptor, json.toString(2))
                }
                index.folderMeta[item.id] = extras.toString()
            } else {
                if (item.id in staged) {
                    val source = absolute(index.pages.getValue(item.id))
                    check(source.renameTo(target)) { "无法移动 ${target.name}" }
                    afterWrite(target, source.path)
                    index.pages[item.id] = finalRelative(item)
                }
                writePageHead(item, target, keys.getValue(item.id), beforeWrite, afterWrite)
            }
            index.keys[item.id] = keys.getValue(item.id)
        }
        removedFolders.filter { it.exists() }.forEach { it.deleteRecursively() }
    }

    private fun descriptorExtras(json: JSONObject) = JSONObject(json.toString()).apply {
        listOf("id", "title", "sections", "children", "pages", "expanded", "focusedSectionId", "selectedPageSectionId",
            "selectedPageId", "pagePaneMode", "activeNotebookId").forEach(::remove)
    }

    private fun pageMetadata(page: JSONObject) = JSONObject(page.toString()).apply { remove("id"); remove("title") }

    private fun writePageHead(item: Placement, target: File, key: String, beforeWrite: (File) -> Unit, afterWrite: (File, String?) -> Unit) {
        val meta = pageMetadata(item.json)
        index.pageOwner[item.id] = item.owner
        if (target.isFile && item.id in index.pages) {
            val stored = index.pageMeta[item.id]?.let(::JSONObject)
            val unchanged = stored != null && WorkspaceJson.fingerprint(stored) == WorkspaceJson.fingerprint(meta)
            if (unchanged && index.keys[item.id] == key) return
            beforeWrite(target)
            val file = target.bufferedReader(Charsets.UTF_8).use(StreamingJson::readObject)
            val content = file.optJSONObject("content") ?: JSONObject()
            atomicJson(target, PageFileFormat.compose(item.id, key, if (unchanged) stored!! else meta, content))
            afterWrite(target, null)
            if (!unchanged) index.pageMeta[item.id] = meta.toString()
        } else {
            val pending = pendingFile(item.id)
            val content = if (pending.isFile) pending.bufferedReader(Charsets.UTF_8).use(StreamingJson::readObject)
                else JSONObject().put("version", 2).put("notebookId", item.owner).put("pageId", item.id).put("strokes", JSONArray()).put("objects", JSONArray())
            atomicJson(target, PageFileFormat.compose(item.id, key, meta, content))
            val staged = pending.isFile
            pending.delete()
            index.pages[item.id] = target.relativeTo(root).invariantSeparatorsPath
            index.pageMeta[item.id] = meta.toString()
            // The staged page now lives in the library file: its open document follows it.
            afterWrite(target, if (staged) pending.path else null)
        }
    }

    private fun moveIndex(from: String, to: String, page: Boolean) {
        if (from == to) return
        fun under(path: String) = path == from || path.startsWith("$from/")
        index.pages.entries.filter { under(it.value) }.forEach { it.setValue(to + it.value.substring(from.length)) }
        if (!page) index.folders.entries.filter { under(it.value) }.forEach { it.setValue(to + it.value.substring(from.length)) }
    }

    private fun rewriteHead(file: File, id: String, sortKey: String, page: JSONObject) {
        val content = file.bufferedReader(Charsets.UTF_8).use(StreamingJson::readObject).optJSONObject("content") ?: JSONObject()
        atomicJson(file, PageFileFormat.compose(id, sortKey, page, content))
    }

    /** Where the previous version of a library file is kept: hidden under `.mojian/previous`, never beside a page. */
    fun previousFile(file: File): File {
        val relative = file.relativeTo(root).invariantSeparatorsPath
        return if (relative.startsWith("${FolderNames.HIDDEN_ROOT}/") || relative.startsWith("..")) File(file.path + ".previous")
            else File(root, "${FolderNames.HIDDEN_ROOT}/previous/$relative.previous")
    }

    fun temporaryFile(file: File): File = File(root, "${FolderNames.HIDDEN_ROOT}/tmp/${file.name}.${java.util.UUID.randomUUID()}.tmp")

    private fun atomicText(file: File, text: String) = atomicWrite(file) { it.write(text) }
    private fun atomicJson(file: File, json: JSONObject) = atomicWrite(file) { WorkspaceJson.write(json, it) }

    fun atomicWrite(file: File, write: (java.io.Writer) -> Unit) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Unable to create workspace directory" }
        val temporary = temporaryFile(file).also { it.parentFile!!.mkdirs() }
        try {
            java.io.FileOutputStream(temporary).use { stream ->
                val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.BufferedOutputStream(stream, 256 * 1024), Charsets.UTF_8), 64 * 1024)
                write(writer); writer.flush(); stream.fd.sync()
            }
            if (file.isFile) previousFile(file).also { it.parentFile!!.mkdirs() }.let { file.copyTo(it, overwrite = true) }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun safe(id: String) = runCatching { PageContentRepository.safeId(id) }.isSuccess

    companion object {
        fun newId(prefix: String) = "$prefix-${java.util.UUID.randomUUID().toString().replace("-", "")}"
    }
}
