package com.boxlabs.hexdroid

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Request
import com.boxlabs.hexdroid.connection.ProxyConfig

@RunWith(AndroidJUnit4::class)
class PrivacyPolicyTest {
    @Test fun notificationEntryIsNotExported() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(ComponentName(context, NotificationActivity::class.java), 0)
        assertFalse(info.exported)
    }

    @Test fun selfHostedHttpRequiresExplicitConsent() {
        val client = RemoteContentHttp.client(ProxyConfig())
        try {
            client.newCall(Request.Builder().url("http://127.0.0.1:1/upload").build()).execute()
            fail("Cleartext without consent accepted")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("Cleartext HTTP is not enabled"))
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val worker = Executors.newSingleThreadExecutor()
            val serving = worker.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                }
            }
            val url = "http://127.0.0.1:${server.localPort}/upload"
            val allowed = RemoteContentHttp.client(ProxyConfig(), allowHttpEndpoint = url)
            try {
                allowed.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("ok", it.body.string()) }
                serving.get(5, TimeUnit.SECONDS)
            } finally { allowed.connectionPool.evictAll(); allowed.dispatcher.executorService.shutdown(); worker.shutdownNow() }
        }
    }
}
