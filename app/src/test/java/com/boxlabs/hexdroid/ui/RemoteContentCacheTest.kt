package com.boxlabs.hexdroid.ui

import com.boxlabs.hexdroid.connection.ProxyConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteContentCacheTest {
    @Test fun cachesResponsesAndKeepsNetworksIsolated() = runBlocking {
        val root = Files.createTempDirectory("remote-cache-test").toFile()
        val first = RemoteContentTransport("first", false, root) { ProxyConfig() }
        val second = RemoteContentTransport("second", false, root) { ProxyConfig() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val client = first.client()
            assertSame(client, first.client())
            assertNotEquals(first.cacheScope, second.cacheScope)
            assertNotEquals(client.cache!!.directory, second.client().cache!!.directory)
            val server = ServerSocket(0)
            val url = "http://127.0.0.1:${server.localPort}/image"
            val response = executor.submit {
                server.use {
                    it.soTimeout = 5000
                    it.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nCache-Control: max-age=3600\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                            flush()
                        }
                    }
                }
            }
            val request = Request.Builder().url(url).build()
            val localClient = client.newBuilder().apply {
                interceptors().clear(); networkInterceptors().clear(); dns(okhttp3.Dns.SYSTEM)
            }.build() // loopback-only fixture; production transport is HTTPS-only
            localClient.newCall(request).execute().use { assertEquals("ok", it.body.string()) }
            response.get(5, TimeUnit.SECONDS) // origin is closed; repeat must come from cache
            localClient.newCall(request).execute().use {
                assertEquals("ok", it.body.string())
                assertNotNull(it.cacheResponse)
                assertNull(it.networkResponse)
            }
        } finally {
            first.close()
            second.close()
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }
}
