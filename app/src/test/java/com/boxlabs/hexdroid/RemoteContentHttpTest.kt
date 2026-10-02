package com.boxlabs.hexdroid

import com.boxlabs.hexdroid.connection.ProxyConfig
import com.boxlabs.hexdroid.connection.ProxyType
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteContentHttpTest {
    @Test fun invalidProxyFailsClosed() {
        for (type in listOf(ProxyType.SOCKS5, ProxyType.SOCKS4A)) {
            try {
                RemoteContentHttp.client(ProxyConfig(type = type, host = "", port = 9050))
                fail("Must reject invalid proxy")
            } catch (_: IOException) { }
        }
    }

    @Test fun socks5ResolvesDestinationAtProxy() = checkTunnel(ProxyType.SOCKS5, false)
    @Test fun socks5SupportsAuthentication() = checkTunnel(ProxyType.SOCKS5, true)
    @Test fun socks4aResolvesDestinationAtProxy() = checkTunnel(ProxyType.SOCKS4A, false)

    private fun checkTunnel(type: ProxyType, auth: Boolean) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            val task = executor.submit<String> {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = DataInputStream(socket.getInputStream())
                    val out = socket.getOutputStream()
                    val host: String
                    if (type == ProxyType.SOCKS5) {
                        assertEquals(5, input.readUnsignedByte())
                        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                        assertTrue(methods.contains((if (auth) 2 else 0).toByte()))
                        out.write(byteArrayOf(5, (if (auth) 2 else 0).toByte())); out.flush()
                        if (auth) {
                            assertEquals(1, input.readUnsignedByte())
                            val user = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                            val pass = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                            assertEquals("proxy-user", String(user, Charsets.UTF_8))
                            assertEquals("proxy-pass", String(pass, Charsets.UTF_8))
                            out.write(byteArrayOf(1, 0)); out.flush()
                        }
                        assertEquals(5, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                        assertEquals(0, input.readUnsignedByte())
                        assertEquals(3, input.readUnsignedByte()) // DOMAINNAME, never local DNS
                        host = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.UTF_8)
                        assertEquals(80, input.readUnsignedShort())
                        out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); out.flush()
                    } else {
                        assertEquals(4, input.readUnsignedByte())
                        assertEquals(1, input.readUnsignedByte())
                        assertEquals(80, input.readUnsignedShort())
                        assertEquals(1, input.readInt()) // SOCKS4a remote-DNS sentinel
                        readCString(input) // user ID
                        host = readCString(input)
                        out.write(byteArrayOf(0, 90, 0, 80, 127, 0, 0, 1)); out.flush()
                    }
                    val reader = socket.getInputStream().bufferedReader()
                    assertEquals("GET /image.png HTTP/1.1", reader.readLine())
                    while (!reader.readLine().isNullOrEmpty()) { }
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                    out.flush()
                    host
                }
            }
            val client = RemoteContentHttp.client(ProxyConfig(type, "127.0.0.1", server.localPort,
                if (auth) "proxy-user" else null, if (auth) "proxy-pass" else null))
            try {
                client.newCall(Request.Builder().url("http://does-not-resolve.invalid/image.png").build()).execute().use {
                    assertEquals("ok", it.body.string())
                }
                assertEquals("does-not-resolve.invalid", task.get(5, TimeUnit.SECONDS))
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                executor.shutdownNow()
            }
        }
    }

    private fun readCString(input: DataInputStream): String = buildString {
        while (true) {
            val b = input.readUnsignedByte()
            if (b == 0) break
            append(b.toChar())
        }
    }
}
