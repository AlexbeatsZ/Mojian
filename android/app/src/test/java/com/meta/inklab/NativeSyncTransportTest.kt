package com.meta.inklab

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

class NativeSyncTransportTest {
    @Test fun redirectsAreRejectedWithoutForwardingBearerCredentials() {
        val redirects = AtomicInteger()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 2000 }
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    check(reader.readLine().startsWith("GET /manifest "))
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val response = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${server.localPort}/sink\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
                try {
                    server.accept().use { socket ->
                        redirects.incrementAndGet()
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    }
                } catch (_: SocketTimeoutException) { }
            } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true; start() }
        try {
            val transport = NativeSyncHttpTransport("http://127.0.0.1:${server.localPort}", "private-test-token")
            val error = assertThrows(java.io.IOException::class.java) { transport.manifest { false } }
            assertTrue(error.message!!.contains("302"))
            assertFalse(error.message!!.contains("private-test-token"))
            worker.join(5000)
            assertFalse("Loopback server did not finish", worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback server failed", it) }
            assertEquals(0, redirects.get())
        } finally { server.close(); worker.join(3000) }
    }
}
