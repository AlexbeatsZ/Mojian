package com.meta.inklab

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeSyncPublicEndpointTest {
    @Test fun disposablePublicBackendRoundTripsThroughActualAndroidTransport() {
        assumeTrue(System.getenv("MOJIAN_PUBLIC_SYNC_TEST_CONFIRM") == "disposable-backend")
        val endpoint = requireNotNull(System.getenv("MOJIAN_TEST_SYNC_URL"))
        val token = requireNotNull(System.getenv("MOJIAN_TEST_SYNC_TOKEN"))
        require(endpoint.startsWith("https://"))
        val parent = File(System.getProperty("java.io.tmpdir"), ".agents").apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "mojian-public-android").toFile()
        val transport = NativeSyncHttpTransport(endpoint, token)
        val idle: (() -> Unit) -> Boolean = { action -> action(); true }
        try {
            fun workspace(name: String): File = File(directory, name).apply {
                mkdirs()
                File(this, ".mojian-native-workspace").writeText("disposable public transport verification")
                val libraryHash = transport.manifest { false }.files["library.json"]?.hash
                if (libraryHash == null) File(this, "library.json").writeText("{}")
                else transport.download(libraryHash, File(this, "library.json")) { false }
            }
            val first = workspace("first")
            val second = workspace("second")
            val sender = NativeSyncClient(first, endpoint, token)
            val receiver = NativeSyncClient(second, endpoint, token)
            sender.sync(idle)
            assertTrue(sender.status, sender.status.startsWith("本轮已同步"))
            receiver.sync(idle)
            assertTrue(receiver.status, receiver.status.startsWith("本轮已同步"))
            val relative = "notebooks/public-android/pages/${UUID.randomUUID()}.json"
            val source = File(first, relative).apply { parentFile!!.mkdirs(); writeText("{\"probe\":\"安卓公网同步\"}") }
            sender.sync(idle)
            assertTrue(sender.status, sender.status.startsWith("本轮已同步"))
            receiver.sync(idle)
            assertTrue(receiver.status, receiver.status.startsWith("本轮已同步"))
            val received = File(second, relative)
            assertEquals(source.readText(), received.readText())
            received.writeText("{\"probe\":\"return edit\"}")
            receiver.sync(idle)
            sender.sync(idle)
            assertEquals(received.readText(), source.readText())
            assertTrue(source.delete())
            sender.sync(idle)
            receiver.sync(idle)
            assertFalse(received.exists())
            assertNull(transport.manifest { false }.files.getValue(relative).hash)
        } finally { directory.deleteRecursively() }
    }
}
