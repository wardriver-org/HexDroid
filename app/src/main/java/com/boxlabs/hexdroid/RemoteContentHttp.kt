package com.boxlabs.hexdroid

import com.boxlabs.hexdroid.connection.ProxyConfig
import com.boxlabs.hexdroid.connection.ProxyType
import com.boxlabs.hexdroid.connection.SocksProxy
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Per-network HTTP transport. Destination DNS and SOCKS authentication stay in the tunnel. */
internal object RemoteContentHttp {
    fun client(proxy: ProxyConfig, allowHttpEndpoint: String? = null, allowOnionHttp: Boolean = false, preview: Boolean = false): OkHttpClient {
        if (proxy.type != ProxyType.NONE && !proxy.enabled) {
            throw IOException("Invalid SOCKS configuration; refusing direct remote content requests")
        }
        val policy = okhttp3.Interceptor { chain ->
            val url = chain.request().url.toString()
            if (preview && !PreviewPolicy.allowedUrl(url)) throw IOException("Preview destination blocked")
            if (HttpPolicy.isOnion(url) && !proxy.enabled) throw IOException("Onion content requires a SOCKS route")
            val allowed = chain.request().url.isHttps ||
                (allowHttpEndpoint != null && HttpPolicy.sameOrigin(allowHttpEndpoint, url)) ||
                (allowOnionHttp && proxy.type == ProxyType.SOCKS5 && proxy.host == "127.0.0.1" &&
                    proxy.port == 9050 && HttpPolicy.isOnion(url))
            if (!allowed) throw IOException("Cleartext HTTP is not enabled for this request")
            chain.proceed(chain.request())
        }
        val builder = OkHttpClient.Builder()
            .addInterceptor(policy)
            .addNetworkInterceptor(policy)
            .proxy(Proxy.NO_PROXY) // Never consult a system proxy selector or fall back to another route.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followSslRedirects(false)
        if (preview) {
            builder.followRedirects(false)
            if (proxy.type == ProxyType.NONE) builder.dns(Dns { host ->
                Dns.SYSTEM.lookup(host).also { addresses ->
                    if (addresses.isEmpty() || addresses.any { !PreviewPolicy.publicAddress(it) })
                        throw java.net.UnknownHostException("Private preview address blocked")
                }
            })
        }
        if (proxy.type != ProxyType.NONE) {
            // OkHttp needs an address for route selection. Retain the original hostname
            // on a sentinel address; the socket ignores its bytes and asks SOCKS to resolve it.
            builder.dns(Dns { host -> listOf(InetAddress.getByAddress(host, byteArrayOf(0, 0, 0, 1))) })
                .socketFactory(TunnelSocketFactory(proxy))
                .retryOnConnectionFailure(false)
        }
        return builder.build()
    }

    private class TunnelSocketFactory(private val proxy: ProxyConfig) : SocketFactory() {
        override fun createSocket(): Socket = TunnelSocket(proxy)
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
            connect(InetSocketAddress.createUnresolved(host, port))
        }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress.createUnresolved(host, port))
            }
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress!!, port)
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket(host.hostAddress!!, port, localHost, localPort)
    }

    private class TunnelSocket(private val proxy: ProxyConfig) : Socket() {
        override fun connect(endpoint: SocketAddress) = connect(endpoint, 15_000)
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IOException("Unsupported address")
            val readTimeout = soTimeout
            try {
                val proxyAddress = resolveAllWithTimeout(proxy.host, null, timeout.takeIf { it > 0 } ?: 15_000).first()
                super.connect(InetSocketAddress(proxyAddress, proxy.port), timeout)
                soTimeout = timeout.takeIf { it > 0 } ?: 15_000
                SocksProxy.negotiate(this, proxy, target.hostString, target.port)
                soTimeout = readTimeout
            } catch (e: Exception) {
                runCatching { close() }
                throw e
            }
        }
    }
}
