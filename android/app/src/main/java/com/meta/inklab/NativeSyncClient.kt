package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class NativeSyncClient(
    root: File,
    endpoint: String,
    token: String,
    private val statusChanged: (String) -> Unit = {},
    private val transport: NativeSyncTransport = NativeSyncHttpTransport(endpoint, token),
) {
    private val root = root.absoluteFile.also { NativeSyncConfiguration.requireDedicatedBoundary(it) }.canonicalFile
    private val endpoint = NativeSyncFiles.endpoint(endpoint).toString()
    private val running = AtomicBoolean()
    private val directory = File(this.root, ".mojian-sync")
    private val stateFile = File(directory, "state.json")
    private val outboxFile = File(directory, "outbox.json")
    private val incomingFile = File(directory, "incoming.json")
    private val conflictFile = File(directory, "conflict.json")
    private val blobs = File(directory, "transfer")
    private var workspaceId = ""
    private var deviceId = UUID.randomUUID().toString()
    private val checkpoint = linkedMapOf<String, NativeSyncEntry>()
    private val hashCache = NativeSyncHashCache()
    private val localMutations = LocalMutationEpoch.forWorkspace(this.root)
    private var serverRevision = 0L
    val hasPendingIncoming: Boolean get() = incomingFile.exists()
    val recoveryDirectory: File get() = directory
    @Volatile var status: String = "等待同步"
        private set

    init {
        NativeSyncConfiguration.requireDedicatedBoundary(root)
        loadState()
    }

    private fun loadState() {
        if (stateFile.exists()) {
            val state = NativeSyncFiles.read(stateFile)
            require(state.getString("endpoint") == this.endpoint) { "此副本已绑定其他服务器，请创建新的独立副本" }
            deviceId = state.getString("deviceId")
            workspaceId = state.getString("workspaceId")
            require(NativeSyncFiles.validId(deviceId)) { "无效设备身份" }
            serverRevision = NativeSyncFiles.safeInteger(state, "revision")
            val manifest = NativeSyncManifest.parse(JSONObject().put("workspaceId", workspaceId.ifEmpty { "unbound" })
                .put("revision", serverRevision).put("files", state.getJSONObject("files")))
            checkpoint.clear()
            checkpoint.putAll(manifest.files)
        }
    }

    private fun setStatus(value: String) {
        status = value
        runCatching { statusChanged(value) }
    }

    private fun persistState() {
        NativeSyncFiles.atomicJson(stateFile, stateJson(checkpoint))
    }

    private fun stateJson(files: Map<String, NativeSyncEntry>): JSONObject {
        val revision = maxOf(serverRevision, files.values.maxOfOrNull { it.version } ?: 0)
        return JSONObject().put("endpoint", endpoint).put("deviceId", deviceId)
            .put("workspaceId", workspaceId).put("revision", revision)
            .put("files", JSONObject().apply { files.forEach { (path, entry) -> put(path, entry.json()) } })
    }

    private fun requireIdentity(manifest: NativeSyncManifest) {
        check(workspaceId.isEmpty() || workspaceId == manifest.workspaceId) { "服务器身份发生变化，已停止同步" }
        check(manifest.revision >= serverRevision) { "服务器版本回退，已停止同步" }
        if (workspaceId == manifest.workspaceId && serverRevision == manifest.revision) return
        workspaceId = manifest.workspaceId
        serverRevision = manifest.revision
        persistState()
    }

    fun recoverIncoming(applyOnIdle: (() -> Unit) -> Boolean = { action -> action(); true }, cancelled: () -> Boolean = { false }): Boolean {
        if (!hasPendingIncoming) return true
        if (!running.compareAndSet(false, true)) return false
        try {
            NativeSyncConfiguration.requireDedicatedBoundary(root)
            java.io.RandomAccessFile(File(directory, "client.lock"), "rw").use { stream ->
                val lock = stream.channel.tryLock() ?: return false
                try {
                    loadState()
                    check(workspaceId.isNotEmpty()) { "接收日志缺少已绑定的工作区身份" }
                    return replayIncoming(applyOnIdle, cancelled)
                } finally { lock.release() }
            }
        } catch (error: Exception) {
            setStatus("接收恢复未完成，禁止打开编辑库：${error.message}")
            return false
        } finally { running.set(false) }
    }

    fun sync(applyOnIdle: (() -> Unit) -> Boolean, cancelled: () -> Boolean = { false }) {
        if (!running.compareAndSet(false, true)) return
        var lockStream: java.io.RandomAccessFile? = null
        var fileLock: java.nio.channels.FileLock? = null
        try {
            NativeSyncConfiguration.requireDedicatedBoundary(root)
            check(directory.isDirectory || directory.mkdirs())
            lockStream = java.io.RandomAccessFile(File(directory, "client.lock"), "rw")
            fileLock = lockStream.channel.tryLock()
            if (fileLock == null) { setStatus("同步正在运行"); return }
            loadState()
            if (!stateFile.exists()) persistState()
            checkCancelled(cancelled)
            if (incomingFile.exists() && !replayIncoming(applyOnIdle, cancelled)) return
            check(File(root, "library.json").isFile) { "本地 library.json 缺失，已停止同步；不能将目录事故作为删除上传" }
            if (conflictFile.exists()) { setStatus("同步冲突尚未解决，双方副本保留在 .mojian-sync/conflicts"); return }
            val scanEpoch = localMutations.capture() ?: run { setStatus("本地写入中，等待下一轮同步"); return }
            val local = hashCache.scan(root, cancelled, rejectSyncthing = true)
            val identity = transport.manifest(cancelled)
            requireIdentity(identity)
            check(identity.files["library.json"]?.let { it.hash != null } != false) { "远端 library.json 已删除，已停止同步并保留本地库" }
            val retriedOutbox = outboxFile.exists()
            if (retriedOutbox) retryOutbox(cancelled)
            checkCancelled(cancelled)
            setStatus("正在检查同步…")
            val remote = if (retriedOutbox) transport.manifest(cancelled) else identity
            requireIdentity(remote)
            check(remote.files["library.json"]?.let { it.hash != null } != false) { "远端 library.json 已删除，已停止同步并保留本地库" }
            val changes = mutableListOf<NativeSyncChange>()
            val incoming = linkedMapOf<String, NativeSyncEntry>()
            val conflicts = mutableListOf<String>()
            val paths = (checkpoint.keys + local.keys + remote.files.keys).toSet()
            require(paths.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == paths.size) { "本地和远端同步路径大小写冲突" }
            paths.sorted().forEach { path ->
                val ours = local[path]
                val base = checkpoint[path]?.hash
                val theirs = remote.files[path]?.hash
                when {
                    ours == theirs -> remote.files[path]?.let { checkpoint[path] = it }
                    ours != base && theirs != base -> conflicts += path
                    ours != base -> changes += NativeSyncChange(path, remote.files[path]?.version ?: 0, ours)
                    remote.files[path] != null -> incoming[path] = remote.files.getValue(path)
                }
            }
            if (conflicts.isNotEmpty()) {
                preserveConflicts(local, remote, conflicts, cancelled, expectedEpoch = scanEpoch)
                setStatus("同步冲突 ${conflicts.size} 项，双方内容已保留在 .mojian-sync/conflicts")
                return
            }
            if (changes.isNotEmpty()) {
                changes.filter { it.hash != null }.forEach { change ->
                    checkCancelled(cancelled)
                    val hash = requireNotNull(change.hash)
                    try { NativeSyncFiles.copyVerified(NativeSyncFiles.safeFile(root, change.path), blob(hash), hash) }
                    catch (error: Exception) { hashCache.invalidate(change.path); throw error }
                }
                val request = JSONObject().put("workspaceId", workspaceId)
                    .put("deviceId", deviceId).put("operationId", UUID.randomUUID().toString())
                    .put("changes", JSONArray().apply { changes.forEach { put(it.json()) } })
                check(localMutations.publishIfUnchanged(scanEpoch) { NativeSyncFiles.atomicJson(outboxFile, request) }) { "本地笔记在快照准备期间发生变化，下一轮重试" }
                retryOutbox(cancelled)
            }
            if (incoming.isNotEmpty()) {
                incoming.values.mapNotNull { it.hash }.distinct().forEach { ensureBlob(it, cancelled) }
                val journal = JSONObject().put("workspaceId", workspaceId).put("files", JSONObject().apply {
                    incoming.forEach { (path, entry) ->
                        put(path, JSONObject().put("entry", entry.json()).put("expectedHash", local[path] ?: JSONObject.NULL))
                    }
                })
                check(localMutations.publishIfUnchanged(scanEpoch) { NativeSyncFiles.atomicJson(incomingFile, journal) }) { "本地笔记在接收准备期间发生变化，下一轮重试" }
                if (!replayIncoming(applyOnIdle, cancelled)) return
            }
            persistState()
            if (!outboxFile.exists() && !incomingFile.exists()) blobs.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            setStatus("本轮已同步，新编辑将在下一轮检查")
        } catch (_: CancellationException) {
            setStatus("同步已暂停，下次继续")
        } catch (_: NativeSyncVersionConflict) {
            setStatus("远端已更新，下次同步重新检查；本地修改已保留")
        } catch (error: Exception) {
            setStatus("同步未完成，本地修改已保留：${error.message}")
        } finally {
            runCatching { fileLock?.release() }
            runCatching { lockStream?.close() }
            running.set(false)
        }
    }

    private fun retryOutbox(cancelled: () -> Boolean) {
        val request = NativeSyncFiles.read(outboxFile)
        check(request.getString("workspaceId") == workspaceId) { "同步发件箱身份不匹配" }
        check(request.getString("deviceId") == deviceId && NativeSyncFiles.validId(request.getString("operationId"))) { "同步操作身份不匹配" }
        val changes = NativeSyncFiles.changes(request.getJSONArray("changes"))
        check(changes.none { it.path == "library.json" && it.hash == null }) { "禁止上传 library.json 删除操作" }
        changes.mapNotNull { it.hash }.distinct().forEach { hash ->
            checkCancelled(cancelled)
            val snapshot = blob(hash)
            check(NativeSyncFiles.hash(snapshot) == hash) { "待上传快照缺失，已停止同步" }
            transport.upload(hash, snapshot, cancelled)
        }
        val receipt = try { transport.commit(request, cancelled) }
        catch (error: NativeSyncVersionConflict) {
            if (error.code == "version_conflict") check(outboxFile.delete()) { "无法清理已过期发件箱" }
            throw error
        }
        check(receipt.workspaceId == workspaceId && receipt.operationId == request.getString("operationId")) { "同步提交身份不匹配" }
        check(receipt.files.keys == changes.map { it.path }.toSet()) { "同步提交回执路径不匹配" }
        changes.forEach { change ->
            val entry = receipt.files[change.path]
            check(entry != null && entry.hash == change.hash && entry.version == receipt.revision && entry.version > change.expectedVersion) { "同步提交回执不匹配" }
        }
        changes.forEach { checkpoint[it.path] = receipt.files.getValue(it.path) }
        serverRevision = maxOf(serverRevision, receipt.revision)
        persistState()
        check(outboxFile.delete()) { "无法清理已提交发件箱" }
    }

    private fun replayIncoming(applyOnIdle: (() -> Unit) -> Boolean, cancelled: () -> Boolean): Boolean {
        val replayEpoch = localMutations.capture() ?: error("本地写入中，等待恢复重试")
        val journal = NativeSyncFiles.read(incomingFile)
        check(journal.getString("workspaceId") == workspaceId) { "接收日志身份不匹配" }
        val files = journal.getJSONObject("files")
        val paths = files.keys().asSequence().toList().sortedWith(compareBy<String> { it == "library.json" }.thenBy { it })
        require(paths.isNotEmpty() && paths.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == paths.size) { "无效接收路径集合" }
        val entries = paths.associateWith { path ->
            require(NativeSyncFiles.validPath(path))
            val record = files.getJSONObject(path)
            require(record.has("expectedHash")) { "缺少接收前置条件" }
            val item = record.getJSONObject("entry")
            require(item.has("hash"))
            val hash = if (item.isNull("hash")) null else item.getString("hash")
            require(hash == null || NativeSyncFiles.validHash(hash))
            val version = NativeSyncFiles.safeInteger(item, "version")
            require(version in 1..serverRevision)
            NativeSyncEntry(hash, version)
        }
        entries.values.mapNotNull { it.hash }.distinct().forEach {
            checkCancelled(cancelled)
            check(NativeSyncFiles.hash(blob(it)) == it) { "本地接收快照缺失，禁止打开未恢复的笔记库" }
        }
        val changed = mutableListOf<String>()
        val prepared = paths.map { path ->
            checkCancelled(cancelled)
            val target = NativeSyncFiles.safeFile(root, path)
            val before = generation(target)
            val actual = NativeSyncFiles.hash(target)
            check(before == generation(target)) { "接收准备期间本地文件发生变化" }
            val item = files.getJSONObject(path)
            val expected = if (item.isNull("expectedHash")) null else item.getString("expectedHash")
            val entry = entries.getValue(path)
            if (actual != expected && actual != entry.hash) changed += path
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val replacement = if (actual != entry.hash && entry.hash != null) File(target.parentFile, ".mojian-incoming-$key.tmp").also {
                NativeSyncFiles.copyVerified(blob(entry.hash), it, entry.hash)
            } else null
            val backup = if (actual != entry.hash && actual != null) File(target.parentFile, ".mojian-previous-$key.tmp").also {
                NativeSyncFiles.copyVerified(target, it, actual)
            } else null
            PreparedIncoming(path, target, before, entry, replacement, backup)
        }
        try {
            if (changed.isNotEmpty()) {
                if (!journal.optBoolean("conflictsPreserved")) {
                    val local = changed.mapNotNull { path -> NativeSyncFiles.hash(NativeSyncFiles.safeFile(root, path))?.let { path to it } }.toMap()
                    val staged = NativeSyncManifest(workspaceId, entries.values.maxOf { it.version }, entries)
                    preserveConflicts(local, staged, changed, cancelled, offline = true, expectedEpoch = replayEpoch)
                    journal.put("conflictsPreserved", true)
                    NativeSyncFiles.atomicJson(incomingFile, journal)
                }
                error("本地页面已变化，双方冲突副本已保留；接收日志等待解决")
            }
            val next = checkpoint.toMutableMap().apply { putAll(entries) }
            val nextState = File(directory, "incoming-checkpoint.tmp")
            NativeSyncFiles.atomicJson(nextState, stateJson(next))
            checkCancelled(cancelled)
            var executed = false
            val applied = applyOnIdle {
                check(!executed) { "接收操作不能重复执行" }
                NativeSyncConfiguration.requireDedicatedBoundary(root)
                prepared.forEach { item ->
                    NativeSyncFiles.safeFile(root, item.path)
                    check(generation(item.target) == item.generation) { "接收前本地文件发生变化，保留日志等待重试" }
                }
                prepared.forEach { item ->
                    item.backup?.let { movePrepared(it, File(item.target.parentFile, "${item.target.name}.previous")) }
                    if (item.entry.hash == null) check(!item.target.exists() || item.target.delete()) { "无法删除已同步文件" }
                    else item.replacement?.let { movePrepared(it, item.target) }
                    hashCache.invalidate(item.path)
                }
                movePrepared(nextState, stateFile)
                checkpoint.putAll(entries)
                check(incomingFile.delete()) { "无法清理接收日志" }
                executed = true
            }
            check(applied == executed) { "空闲接收回调必须同步执行操作或返回 false" }
            if (!applied) setStatus("收到的更新等待当前编辑保存")
            return applied
        } finally {
            prepared.forEach { it.replacement?.delete(); it.backup?.delete() }
        }
    }

    private data class Generation(val size: Long, val modified: java.nio.file.attribute.FileTime, val key: Any?)
    private data class PreparedIncoming(val path: String, val target: File, val generation: Generation?, val entry: NativeSyncEntry, val replacement: File?, val backup: File?)
    private fun generation(file: File): Generation? {
        if (!file.exists()) return null
        val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile) { "接收目标必须是普通文件" }
        return Generation(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
    }
    private fun movePrepared(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun preserveConflicts(local: Map<String, String>, remote: NativeSyncManifest, paths: List<String>, cancelled: () -> Boolean, offline: Boolean = false, expectedEpoch: Long? = null) {
        val conflictEpoch = expectedEpoch ?: localMutations.capture() ?: error("本地写入中，下一轮重新检查冲突")
        check(localMutations.publishIfUnchanged(conflictEpoch) { }) { "本地内容已变化，下一轮重新检查冲突" }
        val folder = File(directory, "conflicts/${remote.revision}-${UUID.randomUUID()}")
        paths.forEach { path ->
            checkCancelled(cancelled)
            local[path]?.let { NativeSyncFiles.copyVerified(NativeSyncFiles.safeFile(root, path), NativeSyncFiles.safeFile(File(folder, "local"), path), it) }
            remote.files[path]?.hash?.let { hash ->
                if (offline) check(NativeSyncFiles.hash(blob(hash)) == hash) { "冲突快照缺失" } else ensureBlob(hash, cancelled)
                NativeSyncFiles.copyVerified(blob(hash), NativeSyncFiles.safeFile(File(folder, "remote"), path), hash)
            }
        }
        val preparedMetadata = File(folder, "conflicts-ready.tmp")
        val preparedMarker = File(directory, "conflict-marker.tmp")
        NativeSyncFiles.atomicJson(preparedMetadata, JSONObject().put("paths", JSONArray(paths))
            .put("remote", remote.json()).put("localDeletions", JSONArray(paths.filter { !local.containsKey(it) })))
        NativeSyncFiles.atomicJson(preparedMarker, JSONObject().put("directory", folder.name).put("paths", JSONArray(paths)).put("revision", remote.revision))
        checkCancelled(cancelled)
        check(localMutations.publishIfUnchanged(conflictEpoch) {
            movePrepared(preparedMetadata, File(folder, "conflicts.json"))
            movePrepared(preparedMarker, conflictFile)
        }) { "冲突快照准备期间本地内容已变化，下一轮重试" }
    }

    private fun blob(hash: String): File {
        require(NativeSyncFiles.validHash(hash))
        require(!Files.isSymbolicLink(blobs.toPath()))
        val result = File(blobs, hash)
        require(!Files.isSymbolicLink(result.toPath()))
        return result
    }

    private fun ensureBlob(hash: String, cancelled: () -> Boolean) {
        val file = blob(hash)
        if (NativeSyncFiles.hash(file) != hash) transport.download(hash, file, cancelled)
        check(NativeSyncFiles.hash(file) == hash) { "接收快照校验失败" }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
    }

    companion object {
        fun validPath(path: String) = NativeSyncFiles.validPath(path)
        fun requireDedicatedWorkspace(root: File) = NativeSyncConfiguration.requireDedicatedWorkspace(root)
        fun cloneWorkspace(source: File, target: File) = NativeSyncConfiguration.cloneWorkspace(source, target)

        fun scanWorkspace(root: File, cancelled: () -> Boolean = { false }): Map<String, String> {
            return NativeSyncHashCache().scan(root, cancelled)
        }
    }
}
