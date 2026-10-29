package com.meta.inklab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeSyncAddressTransportTest {
    private class Route(val name: String, val calls: MutableList<String>) : NativeSyncTransport {
        var offline = false
        var identity = "shared-workspace"
        var loseReceipt = false
        val commits = mutableListOf<String>()
        override fun manifest(cancelled: () -> Boolean): NativeSyncManifest {
            calls += "$name manifest"
            if (offline) throw NativeSyncNetworkFailure("offline")
            return NativeSyncManifest(identity, 1, emptyMap())
        }
        override fun upload(hash: String, source: File, cancelled: () -> Boolean) { calls += "$name upload" }
        override fun download(hash: String, destination: File, cancelled: () -> Boolean) { calls += "$name download" }
        override fun commit(request: JSONObject, cancelled: () -> Boolean): NativeSyncManifest {
            calls += "$name commit"; commits += request.toString()
            if (loseReceipt) { loseReceipt = false; throw NativeSyncNetworkFailure("receipt lost") }
            return NativeSyncManifest(identity, 1, emptyMap(), request.getString("operationId"))
        }
    }

    @Test fun strictOrderResetsEachPassAndStopsAtFirstSuccess() {
        val calls = mutableListOf<String>()
        val routes = (1..3).map { Route("$it", calls) }
        val addresses = (1..3).map { "https://server$it.example/mojian-sync/v1/" }
        val transport = NativeSyncAddressTransport(NativeSyncConfiguration(addresses = addresses), factory = { address, _ -> routes[addresses.indexOf(address)] })
        routes[0].offline = true
        transport.beginPass(); transport.manifest { false }
        assertEquals(listOf("1 manifest", "2 manifest"), calls)
        assertEquals(addresses[1], transport.selectedAddress)
        calls.clear(); routes[0].offline = false
        transport.beginPass(); transport.manifest { false }
        assertEquals(listOf("1 manifest"), calls)
        val request = JSONObject().put("operationId", "stable-operation-12345")
        routes[0].loseReceipt = true
        transport.commit(request) { false }
        assertEquals(routes[0].commits.single(), routes[1].commits.single())
        assertTrue(calls.none { it.startsWith("3") })
    }

    @Test fun foreignWorkspaceIsNotAFallbackAndCancellationDoesNotTryAnotherAddress() {
        val calls = mutableListOf<String>()
        val routes = listOf(Route("1", calls), Route("2", calls))
        val addresses = listOf("https://a.example/", "https://b.example/")
        val transport = NativeSyncAddressTransport(NativeSyncConfiguration(addresses = addresses), factory = { address, _ -> routes[if (address.contains("a.example")) 0 else 1] })
        transport.manifest { false }; routes[0].offline = true; routes[1].identity = "wrong-workspace"
        assertThrows(IllegalStateException::class.java) { transport.manifest { false } }
        calls.clear(); transport.beginPass()
        assertThrows(java.util.concurrent.CancellationException::class.java) { transport.manifest { true } }
        assertTrue(calls.isEmpty())
    }
}
