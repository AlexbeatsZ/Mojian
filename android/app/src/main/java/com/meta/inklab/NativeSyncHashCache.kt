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

    fun scan(root: File, cancelled: () -> Boolean = { false }, rejectSyncthing: Boolean = false): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val insensitive = mutableSetOf<String>()
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            private fun validate(path: Path) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException()
                require(!Files.isSymbolicLink(path)) { "同步副本不允许符号链接" }
                require(!rejectSyncthing || path.fileName.toString() != ".stfolder") { "专用副本内不能包含 Syncthing 文件夹" }
            }

            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                validate(path)
                val relative = root.toPath().relativize(path).toString().replace('\\', '/')
                return if (path != root.toPath() && NativeSyncFiles.ignoredLocalPath(relative)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                validate(path)
                if (!attributes.isRegularFile) return FileVisitResult.CONTINUE
                val relative = root.toPath().relativize(path).toString().replace('\\', '/')
                if (NativeSyncFiles.ignoredLocalPath(relative)) return FileVisitResult.CONTINUE
                if (!NativeSyncFiles.validPath(relative)) {
                    require(!NativeSyncFiles.canonicalCandidate(relative)) { "同步不支持此页面或附件路径，请保留原库并调整命名：$relative" }
                    return FileVisitResult.CONTINUE
                }
                require(insensitive.add(relative.lowercase(Locale.ROOT))) { "本地同步路径大小写冲突" }
                val cached = entries[relative]
                val hash = if (cached != null && cached.size == attributes.size() && cached.modified == attributes.lastModifiedTime() && cached.key == attributes.fileKey()) cached.hash
                else {
                    val file = NativeSyncFiles.safeFile(root, relative)
                    val value = requireNotNull(hashFile(file))
                    val after = Files.readAttributes(path, BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    check(after.isRegularFile && after.size() == attributes.size() && after.lastModifiedTime() == attributes.lastModifiedTime() && after.fileKey() == attributes.fileKey()) { "本地文件正在修改，下次重试" }
                    entries[relative] = Entry(after.size(), after.lastModifiedTime(), after.fileKey(), value)
                    value
                }
                result[relative] = hash
                return FileVisitResult.CONTINUE
            }
        })
        entries.keys.retainAll(result.keys)
        return result
    }
}
