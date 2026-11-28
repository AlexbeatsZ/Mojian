package com.meta.inklab

import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.concurrent.CancellationException

internal class NativeSyncHashCache(private val hashFile: (File) -> String? = NativeSyncFiles::hash) {
    private data class Entry(val size: Long, val modified: java.nio.file.attribute.FileTime, val key: Any?, val hash: String)
    private val entries = mutableMapOf<String, Entry>()

    fun invalidate(path: String) { entries.remove(path) }

    /** Items skipped by the latest scan with their reasons (Syncthing's failed items); every other item keeps syncing. */
    @Volatile var failures: Map<String, String> = emptyMap()
        private set

    /**
     * Every library item: files by SHA-256, folders as [NativeSyncFiles.DIRECTORY]. Links, nested Syncthing folders, names
     * other devices cannot store, non-page files and files still being written are failed items; they never stop the scan.
     */
    fun scan(root: File, cancelled: () -> Boolean = { false }, @Suppress("UNUSED_PARAMETER") rejectSyncthing: Boolean = false): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val failed = linkedMapOf<String, String>()
        val base = root.toPath()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            private fun relative(path: Path) = base.relativize(path).toString().replace('\\', '/')
            private fun checkCancelled() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }

            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (path == base) return FileVisitResult.CONTINUE
                val name = relative(path)
                if (NativeSyncFiles.ignoredLocalPath(name)) return FileVisitResult.SKIP_SUBTREE
                val reason = when {
                    Files.isSymbolicLink(path) -> "符号链接或联接不同步，已跳过"
                    Files.exists(path.resolve(".stfolder")) -> "此文件夹由 Syncthing 管理，已跳过"
                    else -> NativeSyncFiles.failure(name, true)
                }
                if (reason != null) { failed[name] = reason; return FileVisitResult.SKIP_SUBTREE }
                result[name] = NativeSyncFiles.DIRECTORY
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                val name = relative(path)
                if (NativeSyncFiles.ignoredLocalPath(name)) return FileVisitResult.CONTINUE
                if (attributes.isSymbolicLink) { failed[name] = "符号链接或联接不同步，已跳过"; return FileVisitResult.CONTINUE }
                if (!attributes.isRegularFile) { failed[name] = "不是普通文件，已跳过"; return FileVisitResult.CONTINUE }
                NativeSyncFiles.failure(name, false)?.let { failed[name] = it; return FileVisitResult.CONTINUE }
                val cached = entries[name]
                val hash = if (cached != null && cached.size == attributes.size() && cached.modified == attributes.lastModifiedTime() && cached.key == attributes.fileKey()) cached.hash
                else {
                    val value = hashFile(path.toFile()) ?: return FileVisitResult.CONTINUE
                    val after = Files.readAttributes(path, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    // Not mtime: HyperOS rewrites a renamed file's mtime later. Mojian publishes by rename, so a real
                    // replacement changes the inode or size; a file still being written is retried on the next pass.
                    if (!(after.isRegularFile && after.size() == attributes.size() && after.fileKey() == attributes.fileKey())) {
                        failed[name] = "文件仍在写入，下一轮重试"; return FileVisitResult.CONTINUE
                    }
                    entries[name] = Entry(after.size(), after.lastModifiedTime(), after.fileKey(), value)
                    value
                }
                result[name] = hash
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(path: Path, error: java.io.IOException): FileVisitResult {
                failed[relative(path)] = "无法读取：${error.message}"
                return FileVisitResult.CONTINUE
            }
        })
        // Two live names that differ only by case cannot coexist on the server or on other devices.
        result.keys.groupBy { it.lowercase(Locale.ROOT) }.values.filter { it.size > 1 }.forEach { names ->
            names.forEach { name -> failed[name] = "与 ${names.filter { it != name }.joinToString("、")} 只差大小写，其他设备无法同时保存"; result.remove(name) }
        }
        entries.keys.retainAll(result.keys)
        failures = failed
        return result
    }
}
