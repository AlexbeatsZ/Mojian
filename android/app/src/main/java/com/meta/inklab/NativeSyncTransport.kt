package com.meta.inklab

import org.json.JSONObject
import java.io.File
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
    fun upload(hash: String, source: File, cancelled: () -> Boolean)
    fun download(hash: String, destination: File, cancelled: () -> Boolean)
    fun commit(request: JSONObject, cancelled: () -> Boolean): NativeSyncManifest
}

class NativeSyncVersionConflict(val code: String = "version_conflict") : IOException("远端版本已改变，双方修改已保留")
class NativeSyncNetworkFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

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
        json: JSONObject? = null, read: (HttpURLConnection) -> T): T {
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
            if (body != null || json != null) {
                connection.doOutput = true
                if (body != null) {
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
            if (status == 409) {
                val code = runCatching { connection.errorStream?.use { stream ->
                    readJson(stream, 65536).optString("error")
                } }.getOrNull()
                throw NativeSyncVersionConflict(code ?: "unknown_conflict")
            }
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
    override fun commit(request: JSONObject, cancelled: () -> Boolean) = request("commit", "POST", cancelled, json = request, read = ::readManifest)
    override fun upload(hash: String, source: File, cancelled: () -> Boolean) {
        require(NativeSyncFiles.validHash(hash))
        request("blobs/$hash", "PUT", cancelled, body = source) { Unit }
    }
    override fun download(hash: String, destination: File, cancelled: () -> Boolean) {
        require(NativeSyncFiles.validHash(hash))
        request("blobs/$hash", "GET", cancelled) { connection ->
            NativeSyncFiles.atomic(destination) { temporary ->
                java.io.FileOutputStream(temporary).use { output ->
                    connection.inputStream.use { copy(it, output, cancelled) }; output.fd.sync()
                }
                check(NativeSyncFiles.hash(temporary) == hash) { "下载内容不完整" }
            }
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

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("同步已暂停")
    }
}
