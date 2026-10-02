package com.boxlabs.hexdroid.ui

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PreviewRequestsTest {
    @Test fun cancellingPreviewClosesTheActiveSocket(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val worker = Executors.newSingleThreadExecutor()
            val accepted = CountDownLatch(1)
            val socketClosed = CountDownLatch(1)
            val serving = worker.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    accepted.countDown()
                    // Never reply. Cancelling the preview must interrupt this stalled request.
                    if (socket.getInputStream().read() == -1) socketClosed.countDown()
                }
            }
            val client = OkHttpClient.Builder().build()
            val job = launch(Dispatchers.Default) {
                PreviewRequests.fetch(client, Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()) { it.body.string() }
            }
            try {
                assertTrue(accepted.await(5, TimeUnit.SECONDS))
                withTimeout(2000) { job.cancelAndJoin() }
                assertTrue(socketClosed.await(2, TimeUnit.SECONDS))
                serving.get(5, TimeUnit.SECONDS)
            } finally {
                job.cancel()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
                worker.shutdownNow()
            }
        }
    }
}
