package com.boxlabs.hexdroid

import com.boxlabs.hexdroid.connection.ProxyConfig
import com.boxlabs.hexdroid.connection.ProxyType
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.io.File
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

    @Test fun uploadUsesSocks5AndFixedLength() = checkTunnel(ProxyType.SOCKS5, false, true)
    @Test fun uploadUsesAuthenticatedSocks5() = checkTunnel(ProxyType.SOCKS5, true, true)
    @Test fun uploadUsesSocks4a() = checkTunnel(ProxyType.SOCKS4A, false, true)

    private fun checkTunnel(type: ProxyType, auth: Boolean, upload: Boolean = false) {
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
                    fun line(): String = buildString {
                        while (true) {
                            val b = input.readUnsignedByte()
                            if (b == 10) break
                            if (b != 13) append(b.toChar())
                        }
                    }
                    assertEquals(if (upload) "POST /upload HTTP/1.1" else "GET /image.png HTTP/1.1", line())
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    if (upload) {
                        assertNull(headers["transfer-encoding"])
                        val body = ByteArray(headers.getValue("content-length").toInt()).also { input.readFully(it) }
                        val text = String(body, Charsets.UTF_8)
                        assertTrue(text.contains("name=\"file\"; filename=\"test.txt\""))
                        assertTrue(text.contains("upload payload"))
                        assertEquals("Bearer uploader-token", headers["authorization"])
                        assertFalse(text.contains("proxy-pass"))
                    }
                    val response = if (upload) "https://files.example/test.txt" else "ok"
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response".toByteArray())
                    out.flush()
                    host
                }
            }
            val client = RemoteContentHttp.client(ProxyConfig(type, "127.0.0.1", server.localPort,
                if (auth) "proxy-user" else null, if (auth) "proxy-pass" else null))
            try {
                if (upload) {
                    val file = File.createTempFile("upload-test-", ".txt")
                    try {
                        file.writeText("upload payload")
                        val config = UploaderConfig(UploadProvider.CUSTOM, "http://does-not-resolve.invalid/upload",
                            allowHttp = true, authorization = "Bearer uploader-token")
                        val result = MultipartUploader.uploadPrepared(config, file, "test.txt", "text/plain",
                            ProxyConfig(type, "127.0.0.1", server.localPort,
                                if (auth) "proxy-user" else null, if (auth) "proxy-pass" else null))
                        assertEquals("https://files.example/test.txt", result.url)
                        assertNull(result.error)
                    } finally { file.delete() }
                } else {
                    client.newCall(Request.Builder().url("http://does-not-resolve.invalid/image.png").build()).execute().use {
                        assertEquals("ok", it.body.string())
                    }
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
