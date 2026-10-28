package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class NativeSyncEntry(val hash: String?, val version: Long) {
    fun json() = JSONObject().put("hash", hash ?: JSONObject.NULL).put("version", version)
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
                require(insensitive.add(path.lowercase(java.util.Locale.ROOT))) { "Case-colliding synchronized paths" }
                val item = entries.getJSONObject(path)
                require(item.has("hash")) { "Missing content hash" }
                val hash = if (item.isNull("hash")) null else item.getString("hash")
                require(hash == null || NativeSyncFiles.validHash(hash)) { "Invalid content hash" }
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
    fun json() = JSONObject().put("path", path).put("expectedVersion", expectedVersion).put("hash", hash ?: JSONObject.NULL)
}

internal object NativeSyncFiles {
    private val reserved = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\\.|$)", RegexOption.IGNORE_CASE)
    private val pathPattern = Regex("^notebooks/[A-Za-z0-9_-][A-Za-z0-9._-]*/(pages/[A-Za-z0-9_-][A-Za-z0-9._-]*\\.json|assets/[A-Za-z0-9_-][A-Za-z0-9._-]*/[A-Za-z0-9_-][A-Za-z0-9._-]*)$")
    fun validPath(path: String) = path.length < 500 && (path == "library.json" || pathPattern.matches(path)) &&
        !path.endsWith(".previous") && !path.endsWith(".tmp") && !path.contains("-sync-conflict-") &&
        path.split('/').all { !it.endsWith('.') && !reserved.containsMatchIn(it) }
    fun validHash(hash: String) = Regex("^[a-f0-9]{64}$").matches(hash)
    fun ignoredLocalPath(path: String) = path.substringBefore('/') in setOf(".mojian-sync", "_migration", "_backup", "_backups", ".recovery") ||
        path.endsWith(".tmp") || path.endsWith(".previous") || path.contains("-sync-conflict-")
    fun canonicalCandidate(path: String): Boolean {
        val parts = path.split('/')
        return parts.size >= 3 && parts[0].equals("notebooks", ignoreCase = true) &&
            (parts[2].equals("pages", ignoreCase = true) || parts[2].equals("assets", ignoreCase = true))
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
        val hash = if (entry.isNull("hash")) null else entry.getString("hash")
        require(validPath(path) && version >= 0 && (hash == null || validHash(hash)))
        NativeSyncChange(path, version, hash)
    }.also { require(it.size in 1..20000 && it.map { change -> change.path.lowercase(java.util.Locale.ROOT) }.distinct().size == it.size) }
}
