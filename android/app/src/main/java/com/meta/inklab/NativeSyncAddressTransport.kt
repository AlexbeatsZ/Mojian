package com.meta.inklab

import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

/** Ordered addresses of one workspace. Every pass starts at index zero; no health-based sorting. */
internal class NativeSyncAddressTransport(
    configuration: NativeSyncConfiguration,
    private val statusChanged: (String) -> Unit = {},
    factory: (String, String?) -> NativeSyncTransport = { address, pin -> NativeSyncHttpTransport(address, configuration.token, pin) },
) : NativeSyncTransport {
    private val addresses = configuration.serverAddresses
    private val routes = addresses.map { factory(it, configuration.certificatePins[it]) }
    private var current = 0
    private var needsProbe = true
    private var identity: String? = null
    private var revision = 0L
    override val selectedAddress: String? get() = addresses.getOrNull(current)

    override fun beginPass() { current = 0; needsProbe = true }

    private fun verify(manifest: NativeSyncManifest) {
        check(identity == null || manifest.workspaceId == identity) { "候选地址指向不同笔记库，已停止同步" }
        check(manifest.revision >= revision) { "候选地址的服务器版本回退，已停止同步" }
        identity = manifest.workspaceId
        revision = manifest.revision
    }

    private fun <T> attempt(cancelled: () -> Boolean, probe: Boolean = true, action: (NativeSyncTransport) -> T): T {
        var failure: NativeSyncNetworkFailure? = null
        while (current < routes.size) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("同步已暂停")
            statusChanged("正在尝试服务器 ${current + 1}/${routes.size}")
            try {
                if (probe && needsProbe) { verify(routes[current].manifest(cancelled)); needsProbe = false }
                return action(routes[current])
            } catch (error: NativeSyncNetworkFailure) {
                failure = error; current++; needsProbe = true
            }
        }
        throw failure ?: NativeSyncNetworkFailure("所有配置的同步地址均不可用")
    }

    override fun manifest(cancelled: () -> Boolean) = attempt(cancelled, probe = false) {
        it.manifest(cancelled).also { manifest -> verify(manifest); needsProbe = false }
    }
    override fun upload(hash: String, source: File, cancelled: () -> Boolean) = attempt(cancelled) { it.upload(hash, source, cancelled) }
    override fun download(hash: String, destination: File, cancelled: () -> Boolean) = attempt(cancelled) { it.download(hash, destination, cancelled) }
    override fun commit(request: JSONObject, cancelled: () -> Boolean) = attempt(cancelled) { it.commit(request, cancelled).also(::verify) }
}
