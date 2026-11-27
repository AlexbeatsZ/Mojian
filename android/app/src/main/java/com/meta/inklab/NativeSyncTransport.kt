package com.meta.inklab

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.Proxy
import java.net.SocketException
import java.net.UnknownHostException
import java.io.InterruptedIOException
import javax.net.ssl.SSLException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.security.cert.CertificateException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

interface NativeSyncTransport {
    fun beginPass() {}
    val selectedAddress: String? get() = null
    fun manifest(cancelled: () -> Boolean): NativeSyncManifest
    fun manifestPage(since: Long?, cursor: String?, limit: Int, cancelled: () -> Boolean): NativeSyncManifestPage? = null
    fun upload(hash: String, source: File, cancelled: () -> Boolean)
    fun download(hash: String, destination: File, cancelled: () -> Boolean)
    fun commit(request: JSONObject, cancelled: () -> Boolean): NativeSyncManifest
}

class NativeSyncVersionConflict(val code: String = "version_conflict") : IOException("远端版本已改变，双方修改已保留")
class NativeSyncNetworkFailure(message: String, cause: Throwable? = null) : IOException(message, cause)
class NativeSyncBlobMissing : IOException("同步历史内容已过期")

class NativeSyncHttpTransport @JvmOverloads constructor(endpoint: String, private val token: String, certificatePin: String? = null) : NativeSyncTransport {
    private val base: URI = NativeSyncFiles.endpoint(endpoint)
    private val pinnedFactory = certificatePin?.let { pin ->
        require(NativeSyncFiles.validHash(pin)) { "服务器证书指纹无效" }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Client certificate is not supported") }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val leaf = chain.firstOrNull() ?: throw CertificateException("Missing server certificate")
                    leaf.checkValidity()
                    val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(leaf.encoded), expected)) throw CertificateException("Server certificate does not match configured trust")
                }
            }), null)
        }.socketFactory
    }
    init { require(token.isNotBlank() && !token.contains('\r') && !token.contains('\n')) { "无效同步凭据" } }

    private fun <T> request(path: String, method: String, cancelled: () -> Boolean, body: File? = null,
        json: JSONObject? = null, allowedStatuses: Set<Int> = emptySet(),
        range: String? = null, segment: ByteArray? = null, read: (HttpURLConnection) -> T): T {
        checkCancelled(cancelled)
        val uri = base.resolve(path)
        require(uri.scheme == base.scheme && uri.host == base.host && uri.port == base.port)
        val connection = uri.toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        if (connection is HttpsURLConnection && pinnedFactory != null) connection.sslSocketFactory = pinnedFactory
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 30000
            connection.requestMethod = method
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (range != null) connection.setRequestProperty(if (segment == null) "Range" else "Content-Range", range)
            if (body != null || json != null || segment != null) {
                connection.doOutput = true
                if (segment != null) {
                    connection.setRequestProperty("Content-Type", "application/octet-stream")
                    connection.setFixedLengthStreamingMode(segment.size)
                    connection.outputStream.use { it.write(segment) }
                } else if (body != null) {
                    connection.setRequestProperty("Content-Type", "application/octet-stream")
                    connection.setFixedLengthStreamingMode(body.length())
                    connection.outputStream.use { output -> body.inputStream().use { input -> copy(input, output, cancelled) } }
                } else {
                    val bytes = json.toString().toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 4 * 1024 * 1024) { "同步提交过大" }
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
            }
            checkCancelled(cancelled)
            val status = connection.responseCode
            if (status in allowedStatuses) return read(connection)
            if (status == 409) {
                val code = runCatching { connection.errorStream?.use { stream ->
                    readJson(stream, 65536).optString("error")
                } }.getOrNull()
                throw NativeSyncVersionConflict(code ?: "unknown_conflict")
            }
            if (status == 404 && path.startsWith("blobs/")) throw NativeSyncBlobMissing()
            if (status !in 200..299) throw NativeSyncNetworkFailure("同步请求失败 HTTP $status")
            return read(connection)
        } catch (error: IOException) {
            checkCancelled(cancelled)
            if (error is NativeSyncNetworkFailure) throw error
            if (error is SocketException || error is UnknownHostException || error is InterruptedIOException || error is SSLException ||
                error is java.net.ProtocolException || error is java.io.EOFException) throw NativeSyncNetworkFailure("无法连接同步服务：${error.message}", error)
            throw error
        } finally { connection.disconnect() }
    }

    private fun readManifest(connection: HttpURLConnection): NativeSyncManifest {
        return connection.inputStream.use { NativeSyncManifest.parse(readJson(it, 32 * 1024 * 1024)) }
    }

    private fun readJson(input: java.io.InputStream, limit: Int): JSONObject {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "同步响应过大" }
            output.write(buffer, 0, count)
        }
        return JSONObject(String(output.toByteArray(), Charsets.UTF_8))
    }

    override fun manifest(cancelled: () -> Boolean) = request("manifest", "GET", cancelled, read = ::readManifest)
    override fun manifestPage(since: Long?, cursor: String?, limit: Int, cancelled: () -> Boolean): NativeSyncManifestPage? {
        val route = if (since == null) "manifest/pages" else "changes"
        val query = when {
            cursor != null -> "cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8")
            since != null -> "since=$since&limit=$limit"
            else -> "limit=$limit"
        }
        return request("$route?$query", "GET", cancelled, allowedStatuses = setOf(404, 410)) { connection ->
            when (connection.responseCode) {
                404 -> null
                410 -> throw NativeSyncCursorExpired()
                else -> {
                    val payload = connection.inputStream.use { readJson(it, 32 * 1024 * 1024) }
                    require(payload.has("nextCursor")) { "同步分页缺少游标" }
                    NativeSyncManifestPage(NativeSyncManifest.parse(payload),
                        if (payload.isNull("nextCursor")) null else payload.getString("nextCursor"))
                }
            }
        }
    }
    override fun commit(request: JSONObject, cancelled: () -> Boolean) = request("commit", "POST", cancelled, json = request, read = ::readManifest)
    override fun upload(hash: String, source: File, cancelled: () -> Boolean) {
        require(NativeSyncFiles.validHash(hash))
        val total = source.length()
        if (total <= CHUNK_SIZE) return uploadLegacy(hash, source, cancelled)
        val route = "uploads/$hash"
        var offset = request(route, "GET", cancelled, allowedStatuses = setOf(404)) {
            if (it.responseCode != 404) readProgress(it, total)
            else {
                val code = runCatching { it.errorStream?.use { stream -> readJson(stream, 65536).optString("error") } }.getOrNull()
                if (code == "upload_missing") 0L else null
            }
        } ?: return uploadLegacy(hash, source, cancelled)
        var corrections = 0
        RandomAccessFile(source, "r").use { input ->
            while (offset < total) {
                val count = minOf(total - offset, CHUNK_SIZE.toLong()).toInt()
                val bytes = ByteArray(count)
                input.seek(offset); input.readFully(bytes)
                val next = request(route, "PUT", cancelled, allowedStatuses = setOf(404, 409),
                    range = "bytes $offset-${offset + count - 1}/$total", segment = bytes) { connection ->
                    when (connection.responseCode) {
                        404 -> null
                        409 -> {
                            val error = connection.errorStream.use { readJson(it, 65536) }
                            check(error.optString("error") == "upload_offset_mismatch"
                                && NativeSyncFiles.safeInteger(error, "total") == total && ++corrections <= 3) { "同步分段上传进度不一致" }
                            NativeSyncFiles.safeInteger(error, "offset").also { check(it <= total) { "同步分段上传进度无效" } }
                        }
                        else -> readProgress(connection, total).also { check(it == total || it == offset + count) { "同步分段上传未确认" } }
                    }
                }
                if (next == null) return uploadLegacy(hash, source, cancelled)
                offset = next
            }
        }
    }

    private fun uploadLegacy(hash: String, source: File, cancelled: () -> Boolean) {
        request("blobs/$hash", "PUT", cancelled, body = source) { Unit }
    }

    private fun readProgress(connection: HttpURLConnection, total: Long): Long {
        val progress = connection.inputStream.use { readJson(it, 65536) }
        val offset = NativeSyncFiles.safeInteger(progress, "offset")
        check(offset <= total && NativeSyncFiles.safeInteger(progress, "total") == total
            && progress.getBoolean("complete") == (offset == total)) { "同步分段上传进度无效" }
        return offset
    }

    override fun download(hash: String, destination: File, cancelled: () -> Boolean) {
        require(NativeSyncFiles.validHash(hash))
        if (NativeSyncFiles.hash(destination) == hash) return
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        val part = File(destination.parentFile, "$hash.part")
        val journal = File(destination.parentFile, "$hash.progress")
        require(!Files.isSymbolicLink(part.toPath()) && !Files.isSymbolicLink(journal.toPath())) { "无效同步临时文件" }
        val saved = journal.takeIf(File::isFile)?.let(NativeSyncFiles::read)
        var offset = saved?.let { NativeSyncFiles.safeInteger(it, "offset") } ?: 0L
        var total = saved?.let { NativeSyncFiles.safeInteger(it, "total") } ?: 0L
        if (offset > total || total > MAX_BLOB || part.length() < offset) {
            if (journal.exists()) check(journal.delete())
            offset = 0
            total = 0
        }
        val route = "blobs/$hash"
        while (total == 0L || offset < total) {
            val complete = request(route, "GET", cancelled, allowedStatuses = setOf(416),
                range = "bytes=$offset-${offset + CHUNK_SIZE - 1}") { connection ->
                when (connection.responseCode) {
                    416 -> {
                        check(offset == 0L) { "同步下载分段范围无效" }
                        request(route, "GET", cancelled) { full -> readFull(full, part, cancelled) }
                        true
                    }
                    200 -> {
                        if (journal.exists()) check(journal.delete())
                        readFull(connection, part, cancelled)
                        true
                    }
                    206 -> {
                        val range = CONTENT_RANGE.matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                            ?: error("同步下载分段范围无效")
                        val (first, last, length) = range.destructured
                        val start = first.toLong()
                        val end = last.toLong()
                        val reportedTotal = length.toLong()
                        check(start == offset && end >= start && end < reportedTotal && end - start < CHUNK_SIZE
                            && reportedTotal <= MAX_BLOB && (total == 0L || reportedTotal == total)) { "同步下载分段范围无效" }
                        saveRange(connection, part, offset, end - start + 1, cancelled)
                        offset = end + 1
                        total = reportedTotal
                        NativeSyncFiles.atomicJson(journal, JSONObject().put("offset", offset).put("total", total))
                        false
                    }
                    else -> error("同步下载分段响应无效")
                }
            }
            if (complete) break
        }
        if (NativeSyncFiles.hash(part) != hash) {
            part.delete()
            journal.delete()
            error("下载内容不完整")
        }
        Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        if (journal.exists()) check(journal.delete())
    }

    private fun readFull(connection: HttpURLConnection, part: File, cancelled: () -> Boolean) {
        val length = connection.getHeaderFieldLong("Content-Length", -1)
        check(length in 0..MAX_BLOB) { "同步下载长度无效" }
        saveRange(connection, part, 0, length, cancelled)
    }

    private fun saveRange(connection: HttpURLConnection, part: File, offset: Long, length: Long, cancelled: () -> Boolean) {
        val declared = connection.getHeaderFieldLong("Content-Length", -1)
        check(declared == -1L || declared == length) { "同步下载长度不一致" }
        RandomAccessFile(part, "rw").use { output ->
            output.setLength(offset)
            output.seek(offset)
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    checkCancelled(cancelled)
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read < 0) throw NativeSyncNetworkFailure("同步下载分段不完整")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                check(input.read() == -1) { "同步下载分段超出声明长度" }
            }
            output.fd.sync()
        }
    }

    private fun copy(input: java.io.InputStream, output: java.io.OutputStream, cancelled: () -> Boolean) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkCancelled(cancelled)
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= 1024L * 1024 * 1024) { "同步文件过大" }
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val CHUNK_SIZE = 8 * 1024 * 1024
        const val MAX_BLOB = 1024L * 1024 * 1024
        val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("同步已暂停")
    }
}
