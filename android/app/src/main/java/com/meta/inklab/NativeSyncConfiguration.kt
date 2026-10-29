package com.meta.inklab

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class NativeSyncConfiguration(val endpoint: String = DEFAULT_ENDPOINT, val token: String = "", val enabled: Boolean = false) {
    fun save(context: Context, root: File) = save(context.filesDir, root)

    fun save(appPrivateDirectory: File, root: File) {
        if (enabled) { NativeSyncFiles.endpoint(endpoint); require(token.isNotBlank() && !token.contains('\r') && !token.contains('\n')) }
        NativeSyncFiles.atomicJson(configurationFile(appPrivateDirectory, root), JSONObject()
            .put("endpoint", endpoint).put("token", token).put("enabled", enabled))
    }

    override fun toString() = "NativeSyncConfiguration(endpoint=$endpoint, token=[redacted], enabled=$enabled)"

    companion object {
        const val DEFAULT_ENDPOINT = "https://cdn.u3146344.nyat.app:63573/mojian-sync/v1/"
        fun load(context: Context, root: File) = load(context.filesDir, root)
        fun load(appPrivateDirectory: File, root: File): NativeSyncConfiguration {
            val file = configurationFile(appPrivateDirectory, root)
            if (!file.exists()) return NativeSyncConfiguration()
            val value = NativeSyncFiles.read(file)
            return NativeSyncConfiguration(value.getString("endpoint"), value.getString("token"), value.getBoolean("enabled"))
        }

        private fun configurationFile(appPrivateDirectory: File, root: File): File {
            require(!appPrivateDirectory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "同步凭据必须保存在应用私有目录" }
            val key = MessageDigest.getInstance("SHA-256").digest(root.canonicalPath.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            return File(appPrivateDirectory, "native-sync/$key.json")
        }

        fun requireDedicatedWorkspace(root: File) {
            requireDedicatedBoundary(root)
            rejectLinks(root, rejectSyncthing = true)
        }

        internal fun requireDedicatedBoundary(root: File) {
            rejectSyncthingAncestors(root)
            require(File(root, ".mojian-native-workspace").isFile) { "请先创建原生同步专用副本" }
            require(!Files.isSymbolicLink(File(root, ".mojian-native-workspace").toPath()))
            listOf(".mojian-sync", ".mojian-sync/transfer", ".mojian-sync/state.json", ".mojian-sync/outbox.json",
                ".mojian-sync/incoming.json", ".mojian-sync/client.lock", ".mojian-sync/conflicts", ".mojian-sync/conflict.json").forEach {
                require(!Files.isSymbolicLink(File(root, it).toPath())) { "同步状态不能使用符号链接" }
            }
        }

        private fun rejectSyncthingAncestors(root: File) {
            var parent: File? = root.absoluteFile
            while (parent != null) {
                require(!Files.isSymbolicLink(parent.toPath())) { "同步路径不允许符号链接" }
                require(!File(parent, ".stfolder").exists()) { "请在 Syncthing 文件夹之外创建专用副本" }
                parent = parent.parentFile
            }
        }

        private fun rejectLinks(root: File, rejectSyncthing: Boolean = false) {
            if (!root.exists()) return
            Files.walk(root.toPath()).use { paths ->
                paths.forEach {
                    require(!Files.isSymbolicLink(it)) { "同步副本不允许符号链接" }
                    require(!rejectSyncthing || it.fileName.toString() != ".stfolder") { "专用副本内不能包含 Syncthing 文件夹" }
                }
            }
        }

        fun cloneWorkspace(source: File, target: File) {
            rejectSyncthingAncestors(target)
            var ancestor: File? = source.absoluteFile
            while (ancestor != null) {
                require(!Files.isSymbolicLink(ancestor.toPath())) { "源路径不允许符号链接" }
                ancestor = ancestor.parentFile
            }
            rejectLinks(source)
            val origin = source.canonicalFile
            val destination = target.canonicalFile
            require(!destination.exists() && !destination.toPath().startsWith(origin.toPath())) { "请选择原笔记库之外尚不存在的新目录" }
            require(File(origin, "library.json").isFile) { "原笔记库必须使用共享文件夹格式" }
            val before = NativeSyncClient.scanWorkspace(origin)
            check(destination.mkdirs()) { "无法创建副本目录" }
            before.forEach { (path, hash) -> NativeSyncFiles.copyVerified(NativeSyncFiles.safeFile(origin, path), NativeSyncFiles.safeFile(destination, path), hash) }
            check(before == NativeSyncClient.scanWorkspace(origin)) { "原笔记在复制期间发生变化，请重试到新目录" }
            NativeSyncFiles.atomicJson(File(destination, ".mojian-native-workspace"), JSONObject().put("id", java.util.UUID.randomUUID().toString()))
            requireDedicatedWorkspace(destination)
        }
    }
}
