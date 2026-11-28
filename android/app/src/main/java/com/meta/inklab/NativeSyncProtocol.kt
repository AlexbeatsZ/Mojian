package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A file (hash = SHA-256), a folder (hash = [NativeSyncFiles.DIRECTORY]) or a tombstone (hash = null). On the wire a folder is
 * `{"hash":null,"directory":true}` (file-sync-core 0.4.0); inside the client it is one identity string.
 */
data class NativeSyncEntry(val hash: String?, val version: Long) {
    fun json() = JSONObject().put("hash", if (hash == NativeSyncFiles.DIRECTORY) JSONObject.NULL else hash ?: JSONObject.NULL)
        .put("version", version).apply { if (hash == NativeSyncFiles.DIRECTORY) put("directory", true) }
}

data class NativeSyncManifest(val workspaceId: String, val revision: Long, val files: Map<String, NativeSyncEntry>, val operationId: String? = null) {
    fun json() = JSONObject().put("workspaceId", workspaceId).put("revision", revision)
        .put("files", JSONObject().apply { files.forEach { (path, entry) -> put(path, entry.json()) } })
        .apply { operationId?.let { put("operationId", it) } }

    companion object {
        fun parse(source: JSONObject): NativeSyncManifest {
            val id = source.getString("workspaceId")
            require(id.isNotBlank() && id.length <= 200) { "Invalid workspace identity" }
            val revision = NativeSyncFiles.safeInteger(source, "revision")
            val entries = source.getJSONObject("files")
            val files = linkedMapOf<String, NativeSyncEntry>()
            val insensitive = mutableSetOf<String>()
            entries.keys().forEach { path ->
                require(NativeSyncFiles.validPath(path)) { "Invalid synchronized path" }
                val item = entries.getJSONObject(path)
                require(item.has("hash")) { "Missing content hash" }
                val directory = item.optBoolean("directory", false)
                val stored = if (item.isNull("hash")) null else item.getString("hash")
                require(stored == null || NativeSyncFiles.validHash(stored)) { "Invalid content hash" }
                require(!directory || stored == null) { "Folder entries carry no hash" }
                val hash = if (directory) NativeSyncFiles.DIRECTORY else stored
                // Like Syncthing, a tombstone keeps its spelling; only existing items must be unique ignoring case.
                require(hash == null || insensitive.add(path.lowercase(java.util.Locale.ROOT))) { "Case-colliding synchronized paths" }
                val version = NativeSyncFiles.safeInteger(item, "version")
                require(version in 1..revision) { "Invalid file version" }
                files[path] = NativeSyncEntry(hash, version)
            }
            val operationId = if (source.has("operationId")) source.getString("operationId") else null
            require(operationId == null || NativeSyncFiles.validId(operationId)) { "Invalid operation identity" }
            return NativeSyncManifest(id, revision, files, operationId)
        }
    }
}

data class NativeSyncChange(val path: String, val expectedVersion: Long, val hash: String?) {
    // `directory` is omitted for files so v1 operation fingerprints stay byte-identical.
    fun json() = JSONObject().put("path", path).put("expectedVersion", expectedVersion)
        .put("hash", if (NativeSyncFiles.isBlob(hash)) hash else JSONObject.NULL).apply { if (hash == NativeSyncFiles.DIRECTORY) put("directory", true) }
}

internal object NativeSyncFiles {
    /** A folder's identity inside the client; never a blob. */
    const val DIRECTORY = "directory"
    fun isBlob(hash: String?) = hash != null && hash != DIRECTORY
    private val reserved = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\\.|$)", RegexOption.IGNORE_CASE)
    // v2 layout: clients no longer create it, but its tombstones stay in the server manifest.
    fun isLegacy(path: String) = legacyPath.matches(path)
    private val legacyPath = Regex("^(library\\.json|notebooks/[A-Za-z0-9_-][A-Za-z0-9._-]*/(pages/[A-Za-z0-9_-][A-Za-z0-9._-]*\\.json|assets/[A-Za-z0-9_-][A-Za-z0-9._-]*/[A-Za-z0-9_-][A-Za-z0-9._-]*))$")
    private fun storableName(part: String) = part.isNotEmpty() && part.length <= 240 && part != "." && part != ".." &&
        part.none { it.code < 0x20 || it.code == 0x7f || it in "\\:*?\"<>|" } && !part.endsWith('.') && !part.endsWith(' ') && !reserved.containsMatchIn(part)

    /** The folder-library v3 path policy, identical to sync-server/protocol.mjs. */
    fun validPath(path: String): Boolean {
        if (path.isEmpty() || path.length >= 1000 || '\r' in path || '\n' in path || path.endsWith(".previous") || path.endsWith(".tmp") ||
            path.contains(".tmp-") || path.contains("-sync-conflict-")) return false
        val parts = path.split('/')
        if (!parts.all(::storableName)) return false
        if (legacyPath.matches(path)) return true
        if (parts[0] == ".mojian") return parts.size <= 4 && (parts.size < 2 || parts[1] == "assets")
        return parts.all { !it.startsWith('.') || it == FolderNames.DESCRIPTOR }
    }
    fun validHash(hash: String) = Regex("^[a-f0-9]{64}$").matches(hash)

    /** Files that may exist on the server: pages, folder descriptors and page assets. */
    fun isLibraryFile(path: String) = path.endsWith(FolderNames.PAGE_EXTENSION) || path == FolderNames.DESCRIPTOR ||
        path.endsWith("/" + FolderNames.DESCRIPTOR) || (path.startsWith(".mojian/assets/") && path.split('/').size == 4)

    /** Device-local or temporary names that never synchronize and are not reported. */
    fun ignoredLocalPath(path: String): Boolean {
        val parts = path.split('/')
        if (parts[0] == ".mojian" && (parts.size < 2 || parts[1] != "assets")) return parts.size > 1
        return parts.any { part -> part in setOf(".mojian-sync", "_migration", "_backup", "_backups", ".recovery", ".DS_Store", "desktop.ini", "Thumbs.db") ||
            (part.startsWith('.') && part != FolderNames.DESCRIPTOR && part != ".mojian") ||
            part.endsWith(".previous") || part.endsWith(".tmp") || part.contains(".tmp-") || part.contains("-sync-conflict-") }
    }

    /** Why an item that is neither ignored nor valid cannot synchronize, or null when it can. */
    fun failure(path: String, directory: Boolean): String? = when {
        ignoredLocalPath(path) -> null
        !validPath(path) -> "名称含有 \\ : * ? \" < > | 或以空格、句点结尾，其他设备无法保存"
        !directory && !isLibraryFile(path) -> "不是墨笺页面或附件，未同步"
        else -> null
    }
    fun validId(id: String) = Regex("^[A-Za-z0-9_-]{16,80}$").matches(id)
    fun safeInteger(source: JSONObject, key: String): Long {
        val value = source.get(key)
        require(value is Number && value.toDouble().isFinite()) { "Invalid JSON integer" }
        val exact = requireNotNull(value.toString().toBigDecimalOrNull()) { "Invalid JSON integer" }
        val integer = try { exact.longValueExact() } catch (error: ArithmeticException) { throw IllegalArgumentException("Invalid JSON integer", error) }
        require(integer in 0..9007199254740991L) { "Unsafe JSON integer" }
        return integer
    }

    fun endpoint(value: String): URI {
        val uri = URI(value.trim().trimEnd('/') + "/")
        val loopback = uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "[::1]", "::1")
        require(uri.scheme == "https" || uri.scheme == "http" && loopback) { "同步地址必须使用 HTTPS" }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "无效同步地址" }
        return uri
    }

    fun hash(file: File): String? {
        if (!file.exists()) return null
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "同步路径必须是普通文件" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun safeFile(root: File, path: String): File {
        require(validPath(path)) { "无效同步路径" }
        val normalizedRoot = root.canonicalFile
        val file = File(normalizedRoot, path)
        var ancestor: File? = file
        while (ancestor != null && ancestor != normalizedRoot) {
            require(!Files.isSymbolicLink(ancestor.toPath())) { "同步副本不允许符号链接" }
            require(!ancestor.isDirectory || !File(ancestor, ".stfolder").exists()) { "同步路径属于 Syncthing 子文件夹" }
            ancestor = ancestor.parentFile
        }
        require(ancestor == normalizedRoot && file.canonicalFile.toPath().startsWith(normalizedRoot.toPath())) { "同步路径越界" }
        return file
    }

    fun atomicJson(file: File, value: JSONObject) = atomic(file) { target ->
        java.io.FileOutputStream(target).use { output ->
            output.write(value.toString().toByteArray(Charsets.UTF_8)); output.fd.sync()
        }
    }

    fun atomic(file: File, write: (File) -> Unit) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建同步目录" }
        val temporary = File(file.parentFile, "${file.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            write(temporary)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun copyVerified(source: File, target: File, expectedHash: String) {
        atomic(target) { temporary ->
            java.io.FileOutputStream(temporary).use { output ->
                source.inputStream().use { it.copyTo(output, 64 * 1024) }; output.fd.sync()
            }
            check(hash(temporary) == expectedHash) { "文件在复制期间发生变化" }
        }
    }

    fun read(file: File): JSONObject = try { JSONObject(file.readText(Charsets.UTF_8)) }
        catch (error: Exception) { throw IOException("同步状态损坏，已停止同步", error) }

    fun changes(source: JSONArray): List<NativeSyncChange> = (0 until source.length()).map { index ->
        val entry = source.getJSONObject(index)
        require(entry.has("hash"))
        val path = entry.getString("path")
        val version = safeInteger(entry, "expectedVersion")
        val stored = if (entry.isNull("hash")) null else entry.getString("hash")
        val directory = entry.optBoolean("directory", false)
        require(validPath(path) && version >= 0 && (stored == null || validHash(stored)) && (!directory || stored == null))
        NativeSyncChange(path, version, if (directory) DIRECTORY else stored)
    }.also { changes ->
        // A case-only rename retires one spelling and creates another in the same batch.
        val live = changes.filter { it.hash != null }.map { it.path.lowercase(java.util.Locale.ROOT) }
        require(changes.size in 1..20000 && changes.map { it.path }.distinct().size == changes.size && live.distinct().size == live.size)
    }
}
