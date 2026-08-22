package com.meta.inklab

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

data class ImportedAsset(
    val storedName: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val file: File,
)

class AssetStore(
    private val context: Context,
    private val repository: PageContentRepository,
) {
    fun importUri(notebookId: String, pageId: String, uri: Uri): ImportedAsset {
        val metadata = queryMetadata(uri)
        val extension = metadata.displayName.substringAfterLast('.', "").takeIf { it.length in 1..12 }
        val storedName = buildString {
            append(UUID.randomUUID().toString())
            if (extension != null) append('.').append(extension.lowercase())
        }
        val destination = File(repository.assetDirectory(notebookId, pageId), storedName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected file" }
            destination.outputStream().buffered().use(input::copyTo)
        }
        return ImportedAsset(
            storedName = storedName,
            displayName = metadata.displayName,
            mimeType = metadata.mimeType,
            sizeBytes = destination.length(),
            file = destination,
        )
    }

    fun copyFile(notebookId: String, pageId: String, source: File, displayName: String, mimeType: String): ImportedAsset {
        val extension = displayName.substringAfterLast('.', "").takeIf { it.length in 1..12 }
        val storedName = buildString {
            append(UUID.randomUUID().toString())
            if (extension != null) append('.').append(extension.lowercase())
        }
        val destination = File(repository.assetDirectory(notebookId, pageId), storedName)
        source.copyTo(destination, overwrite = true)
        return ImportedAsset(storedName, displayName, mimeType, destination.length(), destination)
    }

    fun assetFile(notebookId: String, pageId: String, storedName: String): File =
        File(repository.assetDirectory(notebookId, pageId), PageContentRepository.safeId(storedName))

    fun stickerDirectory(): File = File(context.filesDir, "stickers").apply { mkdirs() }

    fun importStickerPreset(uri: Uri): ImportedAsset {
        val metadata = queryMetadata(uri)
        val extension = metadata.displayName.substringAfterLast('.', "png").takeIf { it.length in 1..12 } ?: "png"
        val storedName = "${UUID.randomUUID()}.$extension"
        val destination = File(stickerDirectory(), storedName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected sticker" }
            destination.outputStream().buffered().use(input::copyTo)
        }
        return ImportedAsset(storedName, metadata.displayName, metadata.mimeType, destination.length(), destination)
    }

    fun customStickerFiles(): List<File> =
        stickerDirectory().listFiles()?.filter(File::isFile)?.sortedBy { it.name }.orEmpty()

    fun clearPageAssets(notebookId: String, pageId: String) {
        repository.assetDirectory(notebookId, pageId).listFiles()?.filter(File::isFile)?.forEach(File::delete)
    }

    fun clearPageAssetsExcept(notebookId: String, pageId: String, retainedNames: Set<String>) {
        val safeRetained = retainedNames.map(PageContentRepository::safeId).toSet()
        repository.assetDirectory(notebookId, pageId)
            .listFiles()
            ?.filter(File::isFile)
            ?.filterNot { it.name in safeRetained }
            ?.forEach(File::delete)
    }

    private fun queryMetadata(uri: Uri): AssetMetadata {
        var displayName: String? = null
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val mimeType = context.contentResolver.getType(uri) ?: guessMimeType(displayName.orEmpty())
        return AssetMetadata(
            displayName = displayName?.takeIf(String::isNotBlank) ?: "附件",
            mimeType = mimeType,
            sizeBytes = size,
        )
    }

    private fun guessMimeType(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "csv" -> "text/csv"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }

    private data class AssetMetadata(val displayName: String, val mimeType: String, val sizeBytes: Long)
}
