package com.boxlabs.hexdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.boxlabs.hexdroid.IrcViewModel
import com.boxlabs.hexdroid.RemoteContentHttp
import com.boxlabs.hexdroid.connection.ProxyConfig
import okhttp3.OkHttpClient

internal class RemoteContentTransport(
    val networkId: String,
    val proxied: Boolean,
    private val loadProxy: suspend () -> ProxyConfig,
) {
    val cacheScope = java.util.UUID.randomUUID().toString()
    private var closed = false
    private var config: ProxyConfig? = null
    private var http: OkHttpClient? = null
    suspend fun client(): OkHttpClient {
        val current = loadProxy()
        return synchronized(this) {
            check(!closed) { "Remote content transport has been disposed" }
            if (config != current || http == null) {
                http?.dispatcher?.cancelAll()
                http?.connectionPool?.evictAll()
                http = RemoteContentHttp.client(current)
                config = current
            }
            http!!
        }
    }
    fun close() = synchronized(this) {
        closed = true
        http?.dispatcher?.cancelAll()
        http?.connectionPool?.evictAll()
    }
}

internal val LocalRemoteContent = staticCompositionLocalOf<RemoteContentTransport?> { null }

@Composable
internal fun RemoteContentScope(vm: IrcViewModel, networkId: String, proxied: Boolean, routeVersion: Any?, content: @Composable () -> Unit) {
    val transport = remember(vm, networkId, proxied, routeVersion) {
        RemoteContentTransport(networkId, proxied) { vm.remoteContentProxy(networkId) }
    }
    DisposableEffect(transport) { onDispose { transport.close() } }
    CompositionLocalProvider(LocalRemoteContent provides transport, content = content)
}
