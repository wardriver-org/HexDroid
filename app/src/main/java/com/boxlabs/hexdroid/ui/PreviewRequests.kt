package com.boxlabs.hexdroid.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A shared limit also covers synchronous OkHttp calls, which Dispatcher limits don't. */
internal object PreviewRequests {
    private val slots = Semaphore(4)
    suspend fun <T> fetch(client: OkHttpClient, request: Request, consume: (Response) -> T): T = slots.withPermit {
        coroutineScope {
            val call = client.newCall(request)
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                withContext(Dispatchers.IO) {
                    try { call.execute().use(consume) }
                    catch (e: java.io.IOException) {
                        currentCoroutineContext().ensureActive()
                        throw e
                    }
                }
            }
            finally { cancellation.cancel() }
        }
    }
}
