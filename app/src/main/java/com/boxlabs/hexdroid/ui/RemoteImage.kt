/*
 * HexDroidIRC - An IRC Client for Android
 * Copyright (C) 2026 boxlabs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.boxlabs.hexdroid.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.ByteArrayOutputStream

/**
 * Bounded image fetcher for server-supplied images (network icons, metadata avatars). Callers must
 * first check the image-previews opt-in and supply the network transport for proxied profiles. Byte and pixel caps, short timeouts, no redirects; failures mean no image.
 */
internal object RemoteImage {

    /** Decoded images keyed by resolved URL, least-recently-used. */
    private val cache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) =
            size > MAX_CACHED
    }

    /** Fetches in progress, so several rows asking for one URL make one request. */
    private data class Flight(val request: Deferred<ImageBitmap?>, var users: Int = 0)
    private val inFlight = HashMap<String, Flight>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How many decoded images to hold. Covers a member list several times over. */
    private const val MAX_CACHED = 64

    /** Hard ceiling on a downloaded image, applied while streaming. */
    private const val MAX_BYTES = 262_144

    /**
     * Longest edge to decode to, in pixels.
     *
     * Nothing draws these larger than 24dp, and the byte cap alone does not bound memory:
     * a flat image compresses to a few kilobytes and decodes to hundreds of megabytes.
     */
    private const val MAX_EDGE_PX = 256

    /** Cached bitmap for [url], or null when it has not been fetched yet. */
    private fun key(url: String, transport: RemoteContentTransport?) =
        "${transport?.cacheScope ?: "direct"}:$url"

    fun cached(url: String, transport: RemoteContentTransport? = null): ImageBitmap? =
        synchronized(cache) { cache[key(url, transport)] }

    /**
     * Fetch and decode [url], sampled down to [MAX_EDGE_PX]. Returns null on any failure,
     * oversize response, or undecodable payload. Safe to call repeatedly: a cached result
     * short-circuits and concurrent calls for one URL share a single request.
     */
    suspend fun fetch(url: String, transport: RemoteContentTransport? = null): ImageBitmap? {
        val key = key(url, transport)
        cached(url, transport)?.let { return it }
        val flight = synchronized(inFlight) {
            val entry = inFlight[key] ?: run {
                if (inFlight.size >= 64) return null
                Flight(scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { download(url, transport, key) })
                    .also { inFlight[key] = it }
            }
            entry.users++
            entry
        }
        try {
            flight.request.start()
            return flight.request.await()
        } finally {
            synchronized(inFlight) {
                if (--flight.users == 0) {
                    if (inFlight[key] === flight) inFlight.remove(key)
                    flight.request.cancel()
                }
            }
        }
    }

    private val directClient = com.boxlabs.hexdroid.RemoteContentHttp.client(
        com.boxlabs.hexdroid.connection.ProxyConfig(), preview = true)

    private suspend fun download(url: String, transport: RemoteContentTransport?, key: String): ImageBitmap? = try {
        if (!url.startsWith("https://", ignoreCase = true) &&
            !(com.boxlabs.hexdroid.HttpPolicy.isOnion(url) && transport != null)) null else {
            val client = (transport?.client() ?: directClient).newBuilder()
                .followRedirects(false).build()
            PreviewRequests.fetch(client, okhttp3.Request.Builder().url(url).build()) { response ->
                if (!response.isSuccessful || response.body.contentLength() > MAX_BYTES) null else {
                    val buf = ByteArrayOutputStream()
                    response.body.byteStream().use readImage@ { input ->
                        val chunk = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            if (buf.size() + n > MAX_BYTES) return@readImage null
                            buf.write(chunk, 0, n)
                        }
                        decodeSampled(buf.toByteArray())?.asImageBitmap()?.also { bmp ->
                            synchronized(cache) { cache[key] = bmp }
                        }
                    }
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) { null }

    /** Decode [bytes] with the longest edge no larger than [MAX_EDGE_PX]. */
    private fun decodeSampled(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 16_000_000L) return null

        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight))
        }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** The power of two that brings [edgePx] within [MAX_EDGE_PX]. */
    private fun sampleSizeFor(edgePx: Int): Int {
        var sample = 1
        while (edgePx / sample > MAX_EDGE_PX) sample *= 2
        return sample
    }
}
