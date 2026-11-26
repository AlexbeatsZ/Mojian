package com.meta.inklab

import org.json.JSONObject
import java.io.File

data class NativeSyncManifestPage(val manifest: NativeSyncManifest, val nextCursor: String?)
class NativeSyncCursorExpired : java.io.IOException("同步分页游标已过期")

/** Durable remote discovery, independent of the per-path applied baseline. */
internal class NativeSyncManifestFeed(private val transport: NativeSyncTransport, directory: File) {
    private val viewPath = File(directory, "remote-view.json")
    private val partialPath = File(directory, "remote-pages.json")

    fun read(workspace: String, minimumRevision: Long, cancelled: () -> Boolean): NativeSyncManifest {
        val view = viewPath.takeIf(File::isFile)?.let { NativeSyncManifest.parse(NativeSyncFiles.read(it)) }
        if (view != null) verify(view, workspace, 0)
        val saved = partialPath.takeIf(File::isFile)?.let(NativeSyncFiles::read)
        var partial = saved?.let(NativeSyncManifest::parse)
        if (partial != null) verify(partial, workspace, 0)
        var since = saved?.let { if (it.isNull("since")) null else NativeSyncFiles.safeInteger(it, "since") }
        var cursor = saved?.getString("nextCursor")
        if (partial != null && (partial.revision < minimumRevision || (view != null && partial.revision <= view.revision)
                || (since != null && since != view?.revision))) {
            partial = null
            cursor = null
        }
        if (partial == null) since = view?.revision

        var restarted = false
        while (true) {
            val page = try { transport.manifestPage(since, cursor, PAGE_SIZE, cancelled) }
            catch (expired: NativeSyncCursorExpired) {
                if (restarted || (since == null && cursor == null)) throw expired
                restarted = true
                if (partialPath.exists()) check(partialPath.delete())
                partial = null
                since = null
                cursor = null
                continue
            }
            if (page == null) {
                if (partialPath.exists()) check(partialPath.delete())
                return transport.manifest(cancelled)
            }
            val manifest = page.manifest
            verify(manifest, workspace, minimumRevision)
            check(partial == null || (manifest.workspaceId == partial.workspaceId && manifest.revision == partial.revision)) { "同步分页锚点变化" }
            val base = since
            check(base == null || (view?.workspaceId == manifest.workspaceId && manifest.revision >= base
                && manifest.files.values.none { it.version <= base })) { "同步增量超出约定范围" }
            val collected = partial?.files?.toMutableMap() ?: linkedMapOf()
            for ((path, entry) in manifest.files) check(collected.put(path, entry) == null) { "同步清单分页包含重复项" }
            val nextCursor = page.nextCursor
            if (nextCursor != null) {
                check(nextCursor.length in 1..2048 && nextCursor != cursor && manifest.files.isNotEmpty()) { "同步分页游标无效" }
                partial = manifest.copy(files = collected)
                cursor = nextCursor
                NativeSyncFiles.atomicJson(partialPath, partial.json().put("since", base ?: JSONObject.NULL).put("nextCursor", nextCursor))
                continue
            }
            val files = if (base == null) collected else requireNotNull(view).files.toMutableMap().apply { putAll(collected) }
            val result = manifest.copy(files = files)
            val state = result.json()
            NativeSyncManifest.parse(state) // Detect aliases across individual pages or the prior snapshot.
            NativeSyncFiles.atomicJson(viewPath, state)
            if (partialPath.exists()) check(partialPath.delete())
            return result
        }
    }

    private fun verify(manifest: NativeSyncManifest, workspace: String, minimum: Long) {
        check((workspace.isEmpty() || manifest.workspaceId == workspace) && manifest.revision >= minimum) { "服务器身份变化或版本回退，已停止同步" }
    }

    private companion object { const val PAGE_SIZE = 512 }
}
