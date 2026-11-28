package com.meta.inklab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

enum class NativeSyncChoice { LOCAL, REMOTE, BOTH }

/** One conflicted file; [action] is what taking the server's version does here: add, delete or modify. */
data class NativeSyncConflictItem(val path: String, val action: String, val local: File?, val remote: File?, val summary: NativeSyncMerge.Summary?)

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
    private val deferredFile = File(directory, "deferred-incoming.json")
    private fun resumeIncoming(): JSONObject {
        if (deferredFile.isFile) {
            check(!incomingFile.exists()) { "接收日志重复，请保留恢复目录" }
            movePrepared(deferredFile, incomingFile)
        }
        return if (incomingFile.exists()) NativeSyncFiles.read(incomingFile)
            else JSONObject().put("workspaceId", workspaceId).put("files", JSONObject())
    }
    private val conflictFile = File(directory, "conflict.json")
    private val blobs = File(directory, "transfer")
    private val discovery = NativeSyncManifestFeed(transport, directory)
    private var workspaceId = ""
    private var deviceId = UUID.randomUUID().toString()
    private val checkpoint = linkedMapOf<String, NativeSyncEntry>()
    private val hashCache = NativeSyncHashCache()
    private val localMutations = LocalMutationEpoch.forWorkspace(this.root)
    private var serverRevision = 0L
    private var lastSuccessMillis = 0L
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
            lastSuccessMillis = state.optLong("lastSuccessMillis", 0L)
            serverRevision = NativeSyncFiles.safeInteger(state, "revision")
            val manifest = NativeSyncManifest.parse(JSONObject().put("workspaceId", workspaceId.ifEmpty { "unbound" })
                .put("revision", serverRevision).put("files", state.getJSONObject("files")))
            checkpoint.clear()
            checkpoint.putAll(manifest.files)
        }
    }

    private fun setStatus(value: String) {
        val time = if (lastSuccessMillis > 0L) java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(lastSuccessMillis)) else null
        status = if (time == null) value else "$value · 上次完整同步 $time"
        runCatching { statusChanged(status) }
    }

    private fun persistState() {
        NativeSyncFiles.atomicJson(stateFile, stateJson(checkpoint))
    }

    private fun stateJson(files: Map<String, NativeSyncEntry>): JSONObject {
        val revision = maxOf(serverRevision, files.values.maxOfOrNull { it.version } ?: 0)
        return JSONObject().put("endpoint", endpoint).put("deviceId", deviceId)
            .put("workspaceId", workspaceId).put("revision", revision).put("lastSuccessMillis", lastSuccessMillis)
            .put("files", JSONObject().apply { files.forEach { (path, entry) -> put(path, entry.json()) } })
    }

    private fun requireIdentity(manifest: NativeSyncManifest) {
        check(workspaceId.isEmpty() || workspaceId == manifest.workspaceId) { "服务器身份发生变化，已停止同步" }
        check(manifest.revision >= serverRevision) { "服务器版本回退，已停止同步" }
        checkpoint.forEach { (path, previous) ->
            val current = manifest.files[path]
            check(current != null && current.version >= previous.version &&
                (current.version != previous.version || current.hash == previous.hash)) { "服务器丢失已确认版本，已停止同步" }
        }
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
            transport.beginPass()
            NativeSyncConfiguration.requireDedicatedBoundary(root)
            check(directory.isDirectory || directory.mkdirs())
            lockStream = java.io.RandomAccessFile(File(directory, "client.lock"), "rw")
            fileLock = lockStream.channel.tryLock()
            if (fileLock == null) { setStatus("同步正在运行"); return }
            loadState()
            if (!stateFile.exists()) persistState()
            checkCancelled(cancelled)
            if (incomingFile.exists() && !replayIncoming(applyOnIdle, cancelled)) return
            val activeConflicts = conflictMarker()?.second.orEmpty().toSet()
            val heldPaths = activeConflicts + if (deferredFile.isFile)
                NativeSyncFiles.read(deferredFile).getJSONObject("files").keys().asSequence().toSet() else emptySet()
            val scanEpoch = localMutations.capture() ?: run { setStatus("本地写入中，等待下一轮同步"); return }
            val scanned = hashCache.scan(root, cancelled, rejectSyncthing = true)
            val failures = hashCache.failures.toMutableMap()
            fun isHeldFailure(path: String) = failures.keys.any { path == it || path.startsWith("$it/") }
            // A skipped item keeps its acknowledged identity: neither uploaded as new nor deleted remotely.
            val local = scanned.toMutableMap().apply {
                checkpoint.forEach { (path, entry) -> if (isHeldFailure(path) && entry.hash != null) put(path, entry.hash) }
            }
            // A missing or unmounted library must never publish the deletion of every notebook.
            fun notebooks(map: Map<String, String?>) = map.any { (path, hash) -> hash == NativeSyncFiles.DIRECTORY && '/' !in path && !path.startsWith('.') }
            check(notebooks(local) || !notebooks(checkpoint.mapValues { it.value.hash })) { "本地笔记库缺失，已阻止同步删除" }
            val identity = discovery.read(workspaceId, serverRevision, cancelled)
            requireIdentity(identity)
            val retriedOutbox = outboxFile.exists()
            if (retriedOutbox) retryOutbox(cancelled)
            checkCancelled(cancelled)
            setStatus("正在检查同步…")
            val remote = if (retriedOutbox) discovery.read(workspaceId, serverRevision, cancelled) else identity
            requireIdentity(remote)
            val changes = mutableListOf<NativeSyncChange>()
            val incoming = linkedMapOf<String, NativeSyncEntry>()
            val conflicts = mutableListOf<String>()
            val paths = (checkpoint.keys + local.keys + remote.files.keys).toSet()
            paths.sorted().forEach { path ->
                if (path in heldPaths || isHeldFailure(path)) return@forEach
                val ours = local[path]
                when (NativeSyncDecisions.decideEntry(checkpoint[path], ours, remote.files[path])) {
                    NativeSyncDecision.Equal -> remote.files[path]?.let { checkpoint[path] = it }
                    NativeSyncDecision.Conflict -> conflicts += path
                    NativeSyncDecision.Upload -> changes += NativeSyncChange(path, remote.files[path]?.version ?: 0, ours)
                    NativeSyncDecision.Receive -> remote.files[path]?.let { incoming[path] = it }
                    NativeSyncDecision.Failed -> failures[path] = "同一路径一边是文件、一边是文件夹，请保留其中一个"
                }
            }
            // Merged content to publish on top of the remote entry; it is uploaded by the next pass.
            val contents = linkedMapOf<String, String>()
            conflicts.toList().forEach { path ->
                checkCancelled(cancelled)
                val entry = remote.files[path] ?: return@forEach
                when (val merged = mergeConflict(path, checkpoint[path]?.hash, local[path], entry.hash, cancelled)) {
                    null -> {}
                    REMOTE -> { conflicts -= path; incoming[path] = entry }
                    else -> { conflicts -= path; incoming[path] = entry; contents[path] = merged }
                }
            }
            val blocked = heldPaths + conflicts
            if (blocked.isNotEmpty()) {
                // Page identities come from local heads or downloaded blobs; a held page holds only its own group.
                incoming.values.mapNotNull { it.hash }.filter(NativeSyncFiles::isBlob).distinct().forEach { ensureBlob(it, cancelled) }
                val dependencies = pageDependencies(paths + heldPaths) { path -> remote.files[path]?.hash }
                val blockedGroups = blocked.map(dependencies::group).toSet()
                changes.removeAll { dependencies.group(it.path) in blockedGroups }
                incoming.keys.removeAll { dependencies.group(it) in blockedGroups }
                if (conflicts.isNotEmpty()) preserveConflicts(local, remote, conflicts, cancelled, expectedEpoch = scanEpoch)
            }
            // Large imports go up in server-sized batches (deletions first); the next pass continues with the rest.
            changes.sortBy { if (it.hash == null) 0 else 1 }
            val truncated = changes.size > batchLimit
            if (truncated) changes.subList(batchLimit, changes.size).clear()
            lastFailures = failures
            if (changes.isNotEmpty()) {
                setStatus("待上传 ${changes.size} 项，待接收 ${incoming.size} 项")
                changes.filter { NativeSyncFiles.isBlob(it.hash) }.forEach { change ->
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
                setStatus("正在接收 ${incoming.size} 项")
                incoming.values.mapNotNull { it.hash }.filter(NativeSyncFiles::isBlob).distinct().forEach { ensureBlob(it, cancelled) }
                val journal = resumeIncoming()
                journal.getJSONObject("files").apply {
                    incoming.forEach { (path, entry) ->
                        put(path, JSONObject().put("entry", entry.json()).put("expectedHash", local[path] ?: JSONObject.NULL)
                            .apply { contents[path]?.let { put("content", it) } })
                    }
                }
                check(localMutations.publishIfUnchanged(scanEpoch) { NativeSyncFiles.atomicJson(incomingFile, journal) }) { "本地笔记在接收准备期间发生变化，下一轮重试" }
                if (!replayIncoming(applyOnIdle, cancelled)) return
            }
            persistState()
            val unresolved = conflictMarker()?.second.orEmpty()
            if (unresolved.isNotEmpty() || deferredFile.exists() || incomingFile.exists()) {
                setStatus("同步冲突 ${unresolved.size} 项，独立笔记已检查")
                return
            }
            val epoch = localMutations.capture() ?: run { setStatus("本地写入中，等待下一轮同步"); return }
            val current = hashCache.scan(root, cancelled, rejectSyncthing = true)
            val pending = (current.keys + checkpoint.keys).count { current[it] != checkpoint[it]?.hash && !isHeldFailure(it) } + if (truncated) 1 else 0
            if (failures.isNotEmpty()) {
                val (failed, reason) = failures.entries.first()
                setStatus("其他内容已同步；${failures.size} 项无法同步：$failed（$reason）")
                return
            }
            if (pending > 0) { setStatus("本地已保存，待上传 $pending 项"); return }
            checkCancelled(cancelled)
            if (!localMutations.publishIfUnchanged(epoch) {
                lastSuccessMillis = System.currentTimeMillis()
                persistState()
            }) { setStatus("本地写入中，等待下一轮同步"); return }
            blobs.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            setStatus("本轮已同步" + (transport.selectedAddress?.let { " · ${java.net.URI(it).authority}" } ?: ""))
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

    /** The files of the current conflict, as this device would see them after taking the server's version. */
    fun conflictReview(): List<NativeSyncConflictItem> {
        val (folder, paths) = conflictMarker() ?: return emptyList()
        return paths.map { path ->
            val local = NativeSyncFiles.safeFile(File(folder, "local"), path).takeIf(File::isFile)
            val remote = NativeSyncFiles.safeFile(File(folder, "remote"), path).takeIf(File::isFile)
            val summary = if (SyncConflictReview.mergeable(path) && local != null && remote != null && local.length() <= MERGE_LIMIT && remote.length() <= MERGE_LIMIT)
                runCatching { NativeSyncMerge.summarize(readJson(local), readJson(remote)) }.getOrNull() else null
            NativeSyncConflictItem(path, when { local == null -> "add"; remote == null -> "delete"; else -> "modify" }, local, remote, summary)
        }
    }

    /**
     * Resolves some conflicted paths: LOCAL keeps this device's file (uploaded next pass), REMOTE takes the
     * server's, BOTH keeps every id item of both JSON versions. Unchosen paths stay in the conflict.
     */
    fun resolveConflicts(choices: Map<String, NativeSyncChoice>, applyOnIdle: (() -> Unit) -> Boolean, cancelled: () -> Boolean = { false }): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            java.io.RandomAccessFile(File(directory, "client.lock"), "rw").use { stream ->
                val lock = stream.channel.tryLock() ?: return false
                try {
                    loadState()
                    val (folder, paths) = conflictMarker() ?: return true
                    require(choices.isNotEmpty() && choices.keys.all { it in paths }) { "请选择冲突文件保留的版本" }
                    val remote = NativeSyncManifest.parse(NativeSyncFiles.read(File(folder, "conflicts.json")).getJSONObject("remote"))
                    val journal = resumeIncoming()
                    check(journal.getString("workspaceId") == workspaceId) { "接收日志身份不匹配" }
                    val incoming = journal.getJSONObject("files")
                    val reviewPaths = arrayStrings(journal.optJSONArray("reviewPaths")).toMutableSet()
                    choices.forEach { (path, choice) ->
                        val preserved = NativeSyncFiles.safeFile(File(folder, "local"), path).takeIf(File::isFile)
                        check(NativeSyncFiles.hash(NativeSyncFiles.safeFile(root, path)) == preserved?.let(NativeSyncFiles::hash)) { "$path 在冲突后又改过，请先同步重新比较" }
                        val entry = remote.files[path]
                        val remoteFile = NativeSyncFiles.safeFile(File(folder, "remote"), path).takeIf(File::isFile)
                        entry?.hash?.takeIf(NativeSyncFiles::isBlob)?.let { hash -> if (NativeSyncFiles.hash(blob(hash)) != hash) NativeSyncFiles.copyVerified(requireNotNull(remoteFile) { "冲突快照缺失" }, blob(hash), hash) }
                        when (choice) {
                            NativeSyncChoice.LOCAL -> incoming.put(path, JSONObject().put("entry",
                                requireNotNull(entry) { "服务器没有该文件的版本" }.json())
                                .put("expectedHash", preserved?.let(NativeSyncFiles::hash) ?: JSONObject.NULL).put("keepLocal", true))
                            NativeSyncChoice.REMOTE -> incoming.put(path, JSONObject().put("entry", requireNotNull(entry) { "服务器没有该文件的版本" }.json())
                                .put("expectedHash", preserved?.let(NativeSyncFiles::hash) ?: JSONObject.NULL))
                            NativeSyncChoice.BOTH -> {
                                check(entry != null && preserved != null && remoteFile != null && SyncConflictReview.mergeable(path)) { "只有两边都存在的笔记文件可以保留两边" }
                                val bytes = NativeSyncMerge.union(readJson(preserved), readJson(remoteFile)).toString().toByteArray(Charsets.UTF_8)
                                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                                NativeSyncFiles.atomic(blob(hash)) { it.writeBytes(bytes) }
                                incoming.put(path, JSONObject().put("entry", entry.json()).put("expectedHash", NativeSyncFiles.hash(preserved)).put("content", hash))
                            }
                        }
                    }
                    reviewPaths.addAll(choices.keys)
                    NativeSyncFiles.atomicJson(incomingFile, journal.put("reviewPaths", JSONArray(reviewPaths)))
                    if (!replayIncoming(applyOnIdle, cancelled)) return false
                    val remaining = conflictMarker()?.second.orEmpty()
                    setStatus(if (remaining.isEmpty()) "冲突已处理，下一轮同步继续" else "还有 ${remaining.size} 个冲突待处理")
                    return true
                } finally { lock.release() }
            }
        } catch (error: Exception) {
            setStatus("冲突处理未完成，本地内容已保留：${error.message}")
            return false
        } finally { running.set(false) }
    }

    private fun arrayStrings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map(array::getString)

    private fun completeConflictReview(choices: Set<String>) {
        if (choices.isEmpty() || !conflictFile.exists()) return
        val marker = NativeSyncFiles.read(conflictFile)
        val remaining = arrayStrings(marker.getJSONArray("paths")).filterNot(choices::contains)
        if (remaining.isEmpty()) check(conflictFile.delete()) { "无法清理冲突标记" }
        else NativeSyncFiles.atomicJson(conflictFile, marker.put("paths", JSONArray(remaining)))
    }

    private fun conflictMarker(): Pair<File, List<String>>? {
        if (!conflictFile.exists()) return null
        val marker = NativeSyncFiles.read(conflictFile)
        val folder = File(directory, "conflicts/${marker.getString("directory")}")
        val paths = marker.getJSONArray("paths").let { array -> (0 until array.length()).map { array.getString(it) } }
        paths.forEach { require(NativeSyncFiles.validPath(it)) }
        return folder to paths
    }

    private fun readJson(file: File) = file.bufferedReader(Charsets.UTF_8).use(StreamingJson::readObject)

    /** Server-sized upload batches; the next pass continues with the rest. */
    internal var batchLimit = 20000
    /** Items skipped by the latest pass with their reasons; every other item keeps synchronizing. */
    @Volatile var lastFailures: Map<String, String> = emptyMap()
        private set

    /** Page id and PDF owner of each page path, from the local file head or a downloaded remote blob. */
    private fun pageDependencies(paths: Collection<String>, remoteHash: (String) -> String?): NativeSyncDependencies =
        NativeSyncDependencies.fromPages(paths.distinct().filter { it.endsWith(FolderNames.PAGE_EXTENSION) }.map { path ->
            var id: String? = null; var owner: String? = null
            listOfNotNull(File(root, path).takeIf { it.isFile }, remoteHash(path)?.takeIf(NativeSyncFiles::isBlob)?.let(::blob)?.takeIf { it.isFile })
                .forEach { file ->
                    runCatching { PageFileFormat.readHead(file) }.getOrNull()?.let { head ->
                        id = id ?: head.id
                        owner = owner ?: head.page?.optJSONObject("pdfPage")?.optString("sourceOwnerPageId")?.ifEmpty { null }
                    }
                }
            Triple(path, id, owner)
        })

    private fun retryOutbox(cancelled: () -> Boolean) {
        val request = NativeSyncFiles.read(outboxFile)
        check(request.getString("workspaceId") == workspaceId) { "同步发件箱身份不匹配" }
        check(request.getString("deviceId") == deviceId && NativeSyncFiles.validId(request.getString("operationId"))) { "同步操作身份不匹配" }
        val changes = NativeSyncFiles.changes(request.getJSONArray("changes"))
        changes.mapNotNull { it.hash }.filter(NativeSyncFiles::isBlob).distinct().forEach { hash ->
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
        val paths = files.keys().asSequence().toList().sorted()
        require(paths.isNotEmpty()) { "无效接收路径集合" }
        val entries = paths.associateWith { path ->
            require(NativeSyncFiles.validPath(path))
            val record = files.getJSONObject(path)
            require(record.has("expectedHash")) { "缺少接收前置条件" }
            val item = record.getJSONObject("entry")
            require(item.has("hash"))
            val stored = if (item.isNull("hash")) null else item.getString("hash")
            require(stored == null || NativeSyncFiles.validHash(stored))
            val hash = if (item.optBoolean("directory")) NativeSyncFiles.DIRECTORY else stored
            val version = NativeSyncFiles.safeInteger(item, "version")
            require(version in 1..serverRevision)
            NativeSyncEntry(hash, version)
        }
        val contents = paths.associateWith { path ->
            files.getJSONObject(path).optString("content").takeIf(String::isNotEmpty)?.also { require(NativeSyncFiles.validHash(it)) }
        }
        (entries.values.mapNotNull { it.hash }.filter(NativeSyncFiles::isBlob) + contents.values.filterNotNull()).distinct().forEach {
            checkCancelled(cancelled)
            check(NativeSyncFiles.hash(blob(it)) == it) { "本地接收快照缺失，禁止打开未恢复的笔记库" }
        }
        val plannedReview = arrayStrings(journal.optJSONArray("reviewPaths")).toSet()
        require(plannedReview.all(paths::contains)) { "无效冲突审阅记录" }
        val changed = mutableListOf<String>()
        val replan = mutableListOf<String>()
        val listings = mutableMapOf<File, Set<String>>()
        val prepared = paths.mapNotNull { path ->
            checkCancelled(cancelled)
            val target = NativeSyncFiles.safeFile(root, path)
            // A case-insensitive volume resolves `Note.mojian` for `note.mojian`; only the exact spelling is this item.
            val exact = exactPath(path, listings)
            val before = if (exact && target.isFile) generation(target) else null
            val actual = when { !exact -> null; target.isDirectory -> NativeSyncFiles.DIRECTORY; else -> NativeSyncFiles.hash(target) }
            check(before == (if (exact && target.isFile) generation(target) else null)) { "接收准备期间本地文件发生变化" }
            val item = files.getJSONObject(path)
            val expected = if (item.isNull("expectedHash")) null else item.getString("expectedHash")
            val entry = entries.getValue(path)
            val keepLocal = item.optBoolean("keepLocal")
            val wanted = if (keepLocal) expected else contents[path] ?: entry.hash
            if (actual != expected && actual != wanted) {
                // A folder has no content to review: it is planned again on the next pass instead of opening a conflict.
                if (NativeSyncFiles.DIRECTORY in listOf(actual, expected, wanted)) { replan += path; return@mapNotNull null }
                changed += path
            }
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            // Staged outside the notebooks, so a crash never leaves visible files beside a page.
            val replacement = if (actual != wanted && NativeSyncFiles.isBlob(wanted) && !keepLocal) File(directory, "incoming-replacements/$key.tmp").also {
                it.parentFile!!.mkdirs(); NativeSyncFiles.copyVerified(blob(wanted!!), it, wanted)
            } else null
            val backup = if (actual != wanted && NativeSyncFiles.isBlob(actual)) File(directory, "incoming-backups/$key.tmp").also {
                it.parentFile!!.mkdirs(); NativeSyncFiles.copyVerified(target, it, actual!!)
            } else null
            PreparedIncoming(path, target, before, actual, expected, wanted, replacement, backup)
        }
        if (replan.isNotEmpty()) {
            replan.forEach(files::remove)
            if (files.length() == 0) { check(incomingFile.delete()) { "无法清理接收日志" }; return true }
            NativeSyncFiles.atomicJson(incomingFile, journal)
        }
        val livePaths = prepared.map { it.path }
        val rollback = mutableListOf<PreparedIncoming>()
        try {
            val active = conflictMarker()?.second.orEmpty().toSet()
            val newConflicts = changed.filter { it !in active || it in plannedReview }
            if (newConflicts.isNotEmpty()) {
                val local = prepared.mapNotNull { item -> item.actual?.let { item.path to it } }.toMap()
                val staged = NativeSyncManifest(workspaceId, entries.values.maxOf { it.version }, entries)
                preserveConflicts(local, staged, newConflicts, cancelled, offline = true, expectedEpoch = replayEpoch)
            }
            val blocked = (active - plannedReview) + changed
            var known = false
            val remaining = if (blocked.isEmpty()) emptySet() else {
                val wantedByPath = prepared.associate { it.path to it.wanted }
                val dependencies = pageDependencies(livePaths + blocked) { wantedByPath[it] }
                known = true
                val groups = blocked.map(dependencies::group).toSet()
                livePaths.filter { dependencies.group(it) in groups }.toSet()
            }
            val runnable = prepared.filterNot { it.path in remaining }
            var canDefer = known
            for (item in prepared.filter { it.path in remaining && it.expected != it.wanted && it.actual == it.wanted }) {
                val replacement = item.expected?.takeIf(NativeSyncFiles::isBlob)?.let { hash ->
                    val previous = FolderLibrary(root).previousFile(item.target)
                    if (NativeSyncFiles.hash(previous) != hash) null else
                        File(directory, "incoming-replacements/rollback-${UUID.randomUUID()}.tmp").also {
                            NativeSyncFiles.copyVerified(previous, it, hash)
                        }
                }
                if (item.expected != null && replacement == null) { canDefer = false; break }
                // Restoring the acknowledged version: the published bytes are what is on disk now.
                rollback += item.copy(actual = item.wanted, wanted = item.expected, replacement = replacement, backup = null)
            }
            val deferredReview = (plannedReview intersect remaining) - changed.toSet()
            if ((runnable.isEmpty() && rollback.isEmpty()) || (remaining.isNotEmpty() && !canDefer)) {
                if (runnable.isEmpty()) {
                    if (deferredReview != plannedReview)
                        NativeSyncFiles.atomicJson(incomingFile, journal.put("reviewPaths", JSONArray(deferredReview)))
                    if (canDefer) movePrepared(incomingFile, deferredFile)
                }
                setStatus("接收组存在冲突，双方副本及其依赖项已保留")
                return canDefer
            }
            val settled = plannedReview - remaining
            val acknowledged = entries.filterKeys { it !in remaining && it in livePaths }
            val next = checkpoint.toMutableMap().apply { putAll(acknowledged) }
            val nextState = File(directory, "incoming-checkpoint.tmp")
            NativeSyncFiles.atomicJson(nextState, stateJson(next))
            checkCancelled(cancelled)
            var executed = false
            val applied = applyOnIdle {
                check(!executed) { "接收操作不能重复执行" }
                NativeSyncConfiguration.requireDedicatedBoundary(root)
                val publish = runnable + rollback
                publish.forEach { item ->
                    NativeSyncFiles.safeFile(root, item.path)
                    // HyperOS can rewrite a replaced file's mtime later; only a content change blocks replay.
                    if (NativeSyncFiles.isBlob(item.actual))
                        check(generation(item.target) == item.generation || NativeSyncFiles.hash(item.target) == item.actual) { "接收前本地文件发生变化，保留日志等待重试" }
                }
                publishIncoming(publish)
                movePrepared(nextState, stateFile)
                checkpoint.putAll(acknowledged)
                completeConflictReview(settled)
                if (remaining.isEmpty()) check(incomingFile.delete()) { "无法清理接收日志" }
                else NativeSyncFiles.atomicJson(incomingFile, journal.put("files", JSONObject().apply {
                    remaining.forEach { path -> put(path, files.getJSONObject(path)) }
                }).put("reviewPaths", JSONArray(deferredReview)))
                if (remaining.isNotEmpty()) movePrepared(incomingFile, deferredFile)
                executed = true
            }
            check(applied == executed) { "空闲接收回调必须同步执行操作或返回 false" }
            if (!applied) setStatus("收到的更新等待当前编辑保存")
            else if (remaining.isNotEmpty()) setStatus("已接收独立组，冲突组仍待审阅")
            return applied
        } finally {
            (prepared + rollback).forEach { it.replacement?.delete(); it.backup?.delete() }
        }
    }

    /** Exact on-disk spelling of every component below the library root. */
    private fun exactPath(relative: String, listings: MutableMap<File, Set<String>>): Boolean {
        var parent = root
        for (part in relative.split('/')) {
            val names = listings.getOrPut(parent) { parent.list()?.toSet().orEmpty() }
            if (part !in names) return false
            parent = File(parent, part)
        }
        return true
    }

    /**
     * Case-only renames move the old spelling (delete-then-create would delete the only copy on a case-insensitive volume),
     * then folders are created shallow-first, files written, files deleted and folders removed deepest-first. A remotely
     * deleted folder that still holds local content is kept; the next pass uploads it again (Syncthing).
     */
    private fun publishIncoming(items: List<PreparedIncoming>) {
        val library = FolderLibrary(root)
        val done = mutableSetOf<String>()
        fun depth(path: String) = path.count { it == '/' }
        items.filter { it.wanted == null && it.actual != null }.forEach { old ->
            val renamed = items.firstOrNull { it !== old && it.wanted != null && it.actual == null && it.path.equals(old.path, ignoreCase = true) } ?: return@forEach
            val temporary = File(old.target.parentFile, ".mojian-case-${UUID.randomUUID()}.tmp")
            movePrepared(old.target, temporary); movePrepared(temporary, renamed.target)
            done += old.path
            if (renamed.wanted == old.actual) done += renamed.path
            else renamed.replacement?.let { movePrepared(it, renamed.target); done += renamed.path }
            hashCache.invalidate(old.path); hashCache.invalidate(renamed.path)
        }
        items.filter { it.path !in done && it.wanted == NativeSyncFiles.DIRECTORY }.sortedBy { depth(it.path) }.forEach { item ->
            if (NativeSyncFiles.isBlob(item.actual)) check(item.target.delete()) { "无法替换为文件夹：${item.path}" }
            check(item.target.isDirectory || item.target.mkdirs()) { "无法创建文件夹：${item.path}" }
        }
        items.filter { it.path !in done && NativeSyncFiles.isBlob(it.wanted) }.forEach { item ->
            item.target.parentFile?.mkdirs()
            if (item.actual == NativeSyncFiles.DIRECTORY) check(item.target.delete()) { "文件夹不为空，无法替换为文件：${item.path}" }
            item.backup?.let { backup -> library.previousFile(item.target).also { it.parentFile!!.mkdirs() }.let { movePrepared(backup, it) } }
            item.replacement?.let { movePrepared(it, item.target) }
            hashCache.invalidate(item.path)
        }
        items.filter { it.path !in done && it.wanted == null && NativeSyncFiles.isBlob(it.actual) }.forEach { item ->
            item.backup?.let { backup -> library.previousFile(item.target).also { it.parentFile!!.mkdirs() }.let { movePrepared(backup, it) } }
            check(!item.target.exists() || item.target.delete()) { "无法删除已同步文件" }
            hashCache.invalidate(item.path)
        }
        items.filter { it.path !in done && it.wanted == null && it.actual == NativeSyncFiles.DIRECTORY }.sortedByDescending { depth(it.path) }.forEach { item ->
            val remaining = item.target.listFiles()?.filter { child ->
                !NativeSyncFiles.ignoredLocalPath(child.relativeTo(root).invariantSeparatorsPath)
            }.orEmpty()
            if (remaining.isEmpty()) item.target.deleteRecursively()
        }
    }

    private data class Generation(val size: Long, val modified: java.nio.file.attribute.FileTime, val key: Any?)
    private data class PreparedIncoming(val path: String, val target: File, val generation: Generation?, val actual: String?, val expected: String?, val wanted: String?, val replacement: File?, val backup: File?)
    private fun generation(file: File): Generation? {
        if (!file.exists()) return null
        val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile) { "接收目标必须是普通文件" }
        return Generation(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
    }
    private fun movePrepared(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Three-way merges a JSON page/library conflict (shared/fixtures/json-merge.json). Returns [REMOTE]
     * when the local file only differed in volatile fields, the staged merged blob hash, or null.
     */
    private fun mergeConflict(path: String, base: String?, local: String?, remote: String?, cancelled: () -> Boolean): String? {
        if (!SyncConflictReview.mergeable(path) || base == null || local == null || remote == null ||
            listOf(base, local, remote).any { !NativeSyncFiles.isBlob(it) }) return null
        val localFile = NativeSyncFiles.safeFile(root, path)
        if (localFile.length() > MERGE_LIMIT) return null
        return try {
            try { ensureBlob(base, cancelled) } catch (_: NativeSyncBlobMissing) { return null }
            ensureBlob(remote, cancelled)
            if (blob(base).length() > MERGE_LIMIT || blob(remote).length() > MERGE_LIMIT) return null
            val ours = readJson(localFile)
            check(NativeSyncFiles.hash(localFile) == local) { "本地文件在合并期间发生变化" }
            when (val result = NativeSyncMerge.merge(readJson(blob(base)), ours, readJson(blob(remote)))) {
                null -> null
                NativeSyncMerge.Result.Remote -> REMOTE
                is NativeSyncMerge.Result.Merged -> {
                    val bytes = result.document.toString().toByteArray(Charsets.UTF_8)
                    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    NativeSyncFiles.atomic(blob(hash)) { it.writeBytes(bytes) }
                    check(NativeSyncFiles.hash(blob(hash)) == hash) { "合并结果写入失败" }
                    hash
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: OutOfMemoryError) { null }
        catch (_: org.json.JSONException) { null }
    }

    private fun preserveConflicts(local: Map<String, String>, remote: NativeSyncManifest, paths: List<String>, cancelled: () -> Boolean, offline: Boolean = false, expectedEpoch: Long? = null) {
        val conflictEpoch = expectedEpoch ?: localMutations.capture() ?: error("本地写入中，下一轮重新检查冲突")
        check(localMutations.publishIfUnchanged(conflictEpoch) { }) { "本地内容已变化，下一轮重新检查冲突" }
        val folder = File(directory, "conflicts/${remote.revision}-${UUID.randomUUID()}")
        val retained = conflictMarker()
        val previous = retained?.let { (source, _) ->
            NativeSyncManifest.parse(NativeSyncFiles.read(File(source, "conflicts.json")).getJSONObject("remote"))
        }
        val allPaths = (retained?.second.orEmpty() + paths).distinct()
        retained?.let { (source, previousPaths) ->
            previousPaths.filterNot(paths::contains).forEach { path ->
                for (side in listOf("local", "remote")) {
                    val from = NativeSyncFiles.safeFile(File(source, side), path)
                    if (from.isFile) {
                        val to = NativeSyncFiles.safeFile(File(folder, side), path)
                        NativeSyncFiles.copyVerified(from, to, requireNotNull(NativeSyncFiles.hash(from)))
                    }
                }
            }
        }
        val preservedRemote = remote.copy(revision = maxOf(remote.revision, previous?.revision ?: 0),
            files = remote.files.toMutableMap().apply {
                retained?.second.orEmpty().filterNot(paths::contains).forEach { path ->
                    val entry = previous?.files?.get(path)
                    if (entry == null) remove(path) else put(path, entry)
                }
            })
        paths.forEach { path ->
            checkCancelled(cancelled)
            local[path]?.takeIf(NativeSyncFiles::isBlob)?.let { NativeSyncFiles.copyVerified(NativeSyncFiles.safeFile(root, path), NativeSyncFiles.safeFile(File(folder, "local"), path), it) }
            remote.files[path]?.hash?.takeIf(NativeSyncFiles::isBlob)?.let { hash ->
                if (offline) check(NativeSyncFiles.hash(blob(hash)) == hash) { "冲突快照缺失" } else ensureBlob(hash, cancelled)
                NativeSyncFiles.copyVerified(blob(hash), NativeSyncFiles.safeFile(File(folder, "remote"), path), hash)
            }
        }
        val preparedMetadata = File(folder, "conflicts-ready.tmp")
        val preparedMarker = File(directory, "conflict-marker.tmp")
        NativeSyncFiles.atomicJson(preparedMetadata, JSONObject().put("paths", JSONArray(allPaths))
            .put("remote", preservedRemote.json()).put("localDeletions", JSONArray(allPaths.filter { !NativeSyncFiles.safeFile(File(folder, "local"), it).isFile })))
        NativeSyncFiles.atomicJson(preparedMarker, JSONObject().put("directory", folder.name).put("paths", JSONArray(allPaths)).put("revision", preservedRemote.revision))
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
        private const val REMOTE = "remote"
        private const val MERGE_LIMIT = 24L * 1024 * 1024
        fun validPath(path: String) = NativeSyncFiles.validPath(path)
        fun requireDedicatedWorkspace(root: File) = NativeSyncConfiguration.requireDedicatedWorkspace(root)
        fun cloneWorkspace(source: File, target: File) = NativeSyncConfiguration.cloneWorkspace(source, target)

        fun scanWorkspace(root: File, cancelled: () -> Boolean = { false }): Map<String, String> {
            return NativeSyncHashCache().scan(root, cancelled)
        }
    }
}
