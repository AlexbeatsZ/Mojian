package com.meta.inklab

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONObject

internal object WorkspaceFolderSelection {
    fun resolveLocalTree(documentId: String, volumes: Map<String, File>): File {
        val separator = documentId.indexOf(':')
        require(separator > 0) { "请选择本机存储或 SD 卡中的文件夹，不支持云盘或虚拟目录" }
        val volume = requireNotNull(volumes[documentId.substring(0, separator)]) { "无法直接访问此存储卷" }
        val relative = documentId.substring(separator + 1)
        require(!relative.startsWith('/') && !relative.contains('\\') &&
            relative.split('/').none { it == ".." || it == "." }) { "无效的文件夹路径" }
        val root = volume.canonicalFile
        val target = File(root, relative).canonicalFile
        require(target.toPath().startsWith(root.toPath())) { "文件夹必须位于所选存储卷内" }
        return target
    }

    fun createEmptyLibrary(root: File) {
        require(root.isDirectory && root.canWrite() && !Files.isSymbolicLink(root.toPath())) { "请选择可写的本地文件夹" }
        require(root.list()?.isEmpty() == true) { "新笔记库必须使用空文件夹；不会覆盖已有文件" }
        val library = NotebookLibrary(mutableListOf(NotebookWorkspace("notebook-${UUID.randomUUID()}", "新笔记本", mutableListOf())))
        NativeSyncFiles.atomicJson(File(root, "library.json"), SharedWorkspaceCodec.encodeLibrary(library, JSONObject().put("version", 2)))
    }
}
