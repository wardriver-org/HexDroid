package com.boxlabs.hexdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.boxlabs.hexdroid.IrcViewModel
import com.boxlabs.hexdroid.RemoteContentHttp
import com.boxlabs.hexdroid.connection.ProxyConfig
import okhttp3.OkHttpClient
import okhttp3.Cache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object CacheCleanup {
    val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "remote-cache-cleanup").apply { isDaemon = true }
    }
}

internal class RemoteContentTransport(
    val networkId: String,
    val proxied: Boolean,
    private val cacheRoot: File,
    private val allowOnionHttp: Boolean = false,
    private val loadProxy: suspend () -> ProxyConfig,
) {
    val cacheScope = java.util.UUID.randomUUID().toString()
    @Volatile private var closed = false
    private var config: ProxyConfig? = null
    private var http: OkHttpClient? = null
    private var diskCache: Cache? = null
    private val cacheDir = File(cacheRoot, cacheScope)

    private fun closeClient() {
        http?.dispatcher?.cancelAll()
        http?.connectionPool?.evictAll()
        http?.dispatcher?.executorService?.shutdown()
        runCatching { diskCache?.close() }
        diskCache = null
        http = null
    }
    suspend fun client(): OkHttpClient = withContext(Dispatchers.IO) {
        val current = loadProxy()
        synchronized(this@RemoteContentTransport) {
            check(!closed) { "Remote content transport has been disposed" }
            if (config != current || http == null) {
                closeClient()
                // A route/credential change must not reuse another route's HTTP responses.
                cacheDir.deleteRecursively()
                diskCache = Cache(cacheDir, 8L * 1024 * 1024)
                http = RemoteContentHttp.client(current, allowOnionHttp = allowOnionHttp, preview = true).newBuilder().cache(diskCache).build()
                config = current
            }
            http!!
        }
    }
    fun close() {
        closed = true // fail new clients immediately without waiting on disk IO's monitor
        CacheCleanup.worker.execute {
            synchronized(this) {
                closeClient()
                cacheDir.deleteRecursively()
            }
        }
    }

}

/** Root-owned, bounded reuse across server and settings screens; never shares routes. */
internal class RemoteContentPool(cacheDir: File) {
    companion object {
        private val processId = java.util.UUID.randomUUID().toString()
        private val cleanedParents = mutableSetOf<String>()
        @Synchronized private fun processRoot(cacheDir: File): File {
            if (cleanedParents.add(cacheDir.absolutePath)) {
                CacheCleanup.worker.execute {
                    cacheDir.listFiles()?.filter { it.name.startsWith("remote-content-") && it.name != "remote-content-$processId" }
                        ?.forEach { it.deleteRecursively() }
                }
            }
            return File(cacheDir, "remote-content-$processId")
        }
    }
    private val root = File(processRoot(cacheDir), java.util.UUID.randomUUID().toString())
    private data class Key(val networkId: String, val proxied: Boolean, val routeVersion: Any?, val forceOrbot: Boolean)
    private val entries = LinkedHashMap<Key, RemoteContentTransport>(8, 0.75f, true)

    @Synchronized
    fun get(vm: IrcViewModel, networkId: String, proxied: Boolean, routeVersion: Any?, forceOrbot: Boolean = false): RemoteContentTransport {
        val key = Key(networkId, proxied, routeVersion, forceOrbot)
        entries[key]?.let { return it }
        // Retire old configurations of this network immediately.
        val stale = entries.keys.filter { it.networkId == networkId && it.forceOrbot == forceOrbot }
        stale.forEach { entries.remove(it)?.close() }
        val result = RemoteContentTransport(networkId, proxied, root, allowOnionHttp = forceOrbot) {
            if (forceOrbot) ProxyConfig(com.boxlabs.hexdroid.connection.ProxyType.SOCKS5, "127.0.0.1", 9050)
            else vm.remoteContentProxy(networkId)
        }
        entries[key] = result
        while (entries.size > 8) entries.remove(entries.keys.first())?.close()
        return result
    }

    @Synchronized
    fun close() {
        entries.values.forEach { it.close() }
        entries.clear()
        CacheCleanup.worker.execute { root.deleteRecursively() }
    }
}

internal val LocalOrbotRemoteContent = staticCompositionLocalOf<RemoteContentTransport?> { null }

internal val LocalRemoteContent = staticCompositionLocalOf<RemoteContentTransport?> { null }

@Composable
internal fun RemoteContentScope(pool: RemoteContentPool, vm: IrcViewModel, networkId: String, proxied: Boolean, routeVersion: Any?, content: @Composable () -> Unit) {
    val transport = remember(pool, vm, networkId, proxied, routeVersion) {
        pool.get(vm, networkId, proxied, routeVersion)
    }
    val onionTransport = remember(pool, vm, networkId) {
        pool.get(vm, networkId, true, "onion", forceOrbot = true)
    }
    CompositionLocalProvider(LocalRemoteContent provides transport, LocalOrbotRemoteContent provides onionTransport, content = content)
}
