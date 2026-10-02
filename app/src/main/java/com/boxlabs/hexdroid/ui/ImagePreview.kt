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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import com.boxlabs.hexdroid.R

// Twitter / X
private val twitterRegex = Regex(
    """(?:https?://)?(?:www\.)?(?:twitter\.com|x\.com)/([A-Za-z0-9_]{1,50})/status/(\d{1,20})"""
)

private data class TwitterUrlData(
    val username: String,
    val tweetId: String,
    val originalUrl: String,
)

private fun extractTwitterData(url: String): TwitterUrlData? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (uri.rawUserInfo != null || uri.host?.lowercase() !in setOf("twitter.com", "www.twitter.com", "x.com", "www.x.com")) return null
    val m = twitterRegex.find(url) ?: return null
    val user = m.groupValues[1]
    // Filter out Twitter UI path segments that aren't usernames
    if (user.equals("i", ignoreCase = true) || user.equals("intent", ignoreCase = true)) return null
    return TwitterUrlData(username = user, tweetId = m.groupValues[2], originalUrl = url)
}

private data class TwitterMeta(
    val thumbnailUrl: String,
    val hasVideo: Boolean,
    /** Direct, host-validated video URL from fxtwitter, or null if absent/untrusted. */
    val videoUrl: String?,
    val tweetUrl: String,
)

/**
 * fxtwitter is a third-party proxy, so we never feed its `url` straight into a media player
 * without checking it first: only an https URL whose host is exactly Twitter's own video CDN
 * (`video.twimg.com`) is treated as playable. Anything else (a spoofed/compromised response,
 * an unexpected host) falls back to opening the tweet externally. Mirrors how image preview
 * sources are constrained elsewhere in this file.
 */
private fun isPlayableTwitterVideoUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return runCatching {
        val u = android.net.Uri.parse(url)
        u.scheme.equals("https", ignoreCase = true) &&
            u.host.equals("video.twimg.com", ignoreCase = true)
    }.getOrDefault(false)
}

private suspend fun fetchTwitterMeta(data: TwitterUrlData, ctx: Context, client: OkHttpClient): TwitterMeta? = withContext(Dispatchers.IO) {
    runCatching {
        val apiUrl = "https://api.fxtwitter.com/${data.username}/status/${data.tweetId}"
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "HexDroid IRC")
            .build()
        PreviewRequests.fetch(client, request) response@ { response ->
            if (!response.isSuccessful) return@response null
            val json = org.json.JSONObject(String(com.boxlabs.hexdroid.PreviewPolicy.readBounded(response.body.byteStream(), 256 * 1024), Charsets.UTF_8))
            val tweet = json.optJSONObject("tweet") ?: return@response null
            val media = tweet.optJSONObject("media")

            // Prefer video thumbnail
            val videos = media?.optJSONArray("videos")
            if (videos != null && videos.length() > 0) {
                val v = videos.getJSONObject(0)
                val thumb = v.optString("thumbnail_url").takeIf { it.isNotBlank() }
                // The same object carries a direct playable URL (progressive MP4 or HLS).
                // Keep it only if it passes the CDN-host check; otherwise leave it null and
                // the UI falls back to opening the tweet externally.
                val vurl = v.optString("url").takeIf { isPlayableTwitterVideoUrl(it) }
                if (thumb != null) return@response TwitterMeta(thumb, true, vurl, data.originalUrl)
            }

            // Fall back to first photo
            val photos = media?.optJSONArray("photos")
            if (photos != null && photos.length() > 0) {
                val photoUrl = photos.getJSONObject(0).optString("url").takeIf { it.isNotBlank() }
                if (photoUrl != null) return@response TwitterMeta(photoUrl, false, null, data.originalUrl)
            }

            null // text-only tweet — nothing to preview
        }
    }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
}

// YouTube

private val ytRegex = Regex(
    """(?:(?:[a-z]+\.)?youtube\.com/watch\?(?:[^&]*&)*v=|youtu\.be/|(?:[a-z]+\.)?youtube\.com/embed/|(?:[a-z]+\.)?youtube\.com/shorts/)([A-Za-z0-9_-]{11})"""
)

fun extractYouTubeId(url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    if (uri.rawUserInfo != null || uri.host?.lowercase() !in
        setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be")) return null
    return ytRegex.find(url)?.groupValues?.get(1)?.takeIf { it.length == 11 }
}

private fun youtubeThumbnailUrl(videoId: String) =
    "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

// Image URL detection

fun isPreviewableImageUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.contains(".svg") || lower.startsWith("data:")) return false
    val path = lower.substringBefore("?").substringBefore("#")
    return path.endsWith(".jpg") || path.endsWith(".jpeg") ||
           path.endsWith(".png") || path.endsWith(".gif") ||
           path.endsWith(".webp") || path.endsWith(".bmp") ||
           path.endsWith(".avif")
}

// Wifi only option

fun isOnWifi(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false

    // Direct case: the active network is itself Wi-Fi.
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true

    // VPN case: the active network is the tunnel (TRANSPORT_VPN), which does not report the
    // underlying link transport, so the check above misses Wi-Fi-under-VPN and "Wi-Fi only"
    // previews never load while a VPN is connected. The underlying Wi-Fi network still exists
    // alongside the VPN, so look for a validated Wi-Fi network among all current networks.
    // We deliberately do NOT treat VPN as Wi-Fi unconditionally.
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
    }
    return false
}

private val allowedMimeTypes = setOf(
    "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"
)

private sealed interface FetchResult {
    data class Success(val bitmap: Bitmap?, val rawBytes: ByteArray? = null, val isGif: Boolean = false, val pageTitle: String? = null) : FetchResult
    data object TooLarge : FetchResult
    data object Error : FetchResult   // network, 404, decode failure, timeout, etc.
}

// Process-wide HTTP client with explicit timeouts and a 20 MB disk cache, so thumbnails and images
// aren't re-downloaded when scrolling back. Built lazily for the cache directory.
@Volatile private var _httpClient: OkHttpClient? = null

private fun httpClient(ctx: Context): OkHttpClient =
    _httpClient ?: synchronized(OkHttpClient::class.java) {
        _httpClient ?: com.boxlabs.hexdroid.RemoteContentHttp.client(com.boxlabs.hexdroid.connection.ProxyConfig(), preview = true).newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .cache(
                okhttp3.Cache(
                    java.io.File(ctx.applicationContext.cacheDir, "image_preview_cache"),
                    20L * 1024 * 1024  // 20 MB
                )
            )
            .build()
            .also { _httpClient = it }
    }

private suspend fun fetchBitmap(url: String, ctx: Context, client: OkHttpClient): FetchResult = withContext(Dispatchers.IO) {
    // Validate the scheme before handing the URL to OkHttp. The Twitter/fxtwitter
    // API returns a thumbnail URL from a third-party service; if that API were ever to return
    // a non-https URL (e.g. file://, http://, or a redirect to a private IP range), we could
    // inadvertently expose internal resources or send unencrypted traffic. Only https:// is
    // a legitimate source for image previews in a chat client.
    if (!url.startsWith("https://", ignoreCase = true) && !com.boxlabs.hexdroid.HttpPolicy.isOnion(url)) return@withContext FetchResult.Error

    runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "HexDroid IRC")
            .build()

        PreviewRequests.fetch(client, request) response@ { response ->
            if (!response.isSuccessful) return@response FetchResult.Error

            val mime = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
            if (mime == "text/html" && com.boxlabs.hexdroid.HttpPolicy.isOnion(url)) {
                // Read only a title, never execute HTML or fetch page subresources.
                val html = response.body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (output.size() < 65536) {
                        val n = input.read(chunk, 0, minOf(chunk.size, 65536 - output.size()))
                        if (n < 0) break
                        output.write(chunk, 0, n)
                    }
                    output.toString("UTF-8")
                }
                val rawTitle = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.get(1)?.take(2048)
                val title = rawTitle?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString() }
                    ?.replace(Regex("[\\p{Cntrl}\\s]+"), " ")?.trim()?.take(200)
                    ?.takeIf { it.isNotEmpty() } ?: "Onion page"
                return@response FetchResult.Success(bitmap = null, pageTitle = title)
            }
            if (mime == null || mime !in allowedMimeTypes) return@response FetchResult.Error

            val cap = 5 * 1024 * 1024L
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
            if (contentLength > cap) return@response FetchResult.TooLarge

            // Secure hard-capped read
            response.body.byteStream().use { input ->
                val output = ByteArrayOutputStream(8192)
                val buffer = ByteArray(8192)
                var total = 0L
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    total += bytesRead
                    if (total > cap) return@response FetchResult.TooLarge // reject
                    output.write(buffer, 0, bytesRead)
                }

                val bytes = output.toByteArray()
                val isGif = mime == "image/gif"

                // Animated GIFs are rendered from rawBytes via ImageDecoder/Movie in
                // AnimatedGif; Skip the decode entirely and carry
                // only the raw bytes. bitmap is null for GIFs.
                if (isGif) {
                    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
                    if (probe.outWidth <= 0 || probe.outHeight <= 0 ||
                        probe.outWidth.toLong() * probe.outHeight > 16_000_000L) return@response FetchResult.TooLarge
                    return@response FetchResult.Success(bitmap = null, rawBytes = bytes, isGif = true)
                }

                // Decode as software ARGB_8888 with premultiplied alpha: a hardware bitmap or a
                // non-premultiplied one throws when Compose draws it.

                // Downsample to roughly the on-screen size
                val targetMaxDim = 1600
                var inSample = 1
                BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { probe ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
                    if (probe.outWidth <= 0 || probe.outHeight <= 0 ||
                        probe.outWidth.toLong() * probe.outHeight > 16_000_000L) return@response FetchResult.TooLarge
                    val largest = maxOf(probe.outWidth, probe.outHeight)
                    while (largest > 0 && largest / inSample > targetMaxDim) inSample *= 2
                }

                    val decodeOpts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPremultiplied = true
                    inMutable = false
                    inSampleSize = inSample
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)?.let {
                    FetchResult.Success(it, rawBytes = null, isGif = false)
                } ?: FetchResult.Error
            }
        }
    }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; FetchResult.Error }
}

// Preview states

private sealed interface PreviewState {
    data object Idle    : PreviewState
    data object Loading : PreviewState
    data class  Ready(
        val bitmap: Bitmap?,
        val rawBytes: ByteArray? = null,
        val isGif: Boolean = false,
        val isYouTube: Boolean = false,
        val videoId: String = "",
        val isTwitterVideo: Boolean = false,
        val twitterUrl: String = "",
        /** Playable video URL for a Twitter/X video, or null to fall back to opening externally. */
        val twitterVideoUrl: String? = null,
        val pageTitle: String? = null,
    ) : PreviewState
    data class  Failed(val message: String) : PreviewState   // a friendly message
}

// Saver for rememberSaveable: Bitmap isn't Parcelable so we can't persist the image across
// process death. We save the logical state as a string and restore Ready/Loading so the
// bitmap is re-fetched once on restore (not on every recomposition)
private val previewStateSaver = Saver<PreviewState, String>(
    save    = { state -> when (state) {
        is PreviewState.Idle    -> "idle"
        is PreviewState.Loading -> "loading"
        is PreviewState.Failed  -> "failed"   // message is not persisted (process death is rare)
        is PreviewState.Ready   -> "ready" // bitmap can't cross process boundary; re-fetch
    }},
    restore = { saved -> when (saved) {
        "ready"  -> PreviewState.Loading // trigger a re-fetch; loadRequested will be true
        "failed" -> PreviewState.Failed("Failed to load preview")  // transient; re-fetch overwrites it
        else     -> PreviewState.Idle
    }}
)

// Embedded YouTube player

@Composable
private fun YouTubePlayer(videoId: String, onClose: () -> Unit) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView(ctx).apply {
                    lifecycleOwner.lifecycle.addObserver(this)
                    addYouTubePlayerListener(object :
                        com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener() {
                        override fun onReady(
                            youTubePlayer: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
                        ) {
                            youTubePlayer.loadVideo(videoId, 0f)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { lifecycleOwner.lifecycle.removeObserver(it); it.release() },
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(36.dp)
                .focusHighlight(RoundedCornerShape(18.dp))
                .tvInitialFocus()
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
        ) {
            Icon(
                imageVector        = Icons.Default.Close,
                contentDescription = stringResource(R.string.img_close_player),
                tint               = Color.White,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

// Embedded X video player

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun TwitterVideoPlayer(videoUrl: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // One player instance per URL. fxtwitter hands back either a progressive MP4 or an HLS
    // (.m3u8) URL; the default media-source factory picks the right one from the URL because
    // the HLS module is on the classpath, so no explicit MediaSource wiring is needed.
    val exoPlayer = remember(videoUrl) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(videoUrl))
            playWhenReady = true
            prepare()
        }
    }

    // Pause when the app goes to the background so audio doesn't keep playing, and always
    // release the player when this preview leaves composition (scrolled away/dismissed).
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, videoUrl) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.player = null },
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(36.dp)
                .focusHighlight(RoundedCornerShape(18.dp))
                .tvInitialFocus()
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
        ) {
            Icon(
                imageVector        = Icons.Default.Close,
                contentDescription = stringResource(R.string.img_close_player),
                tint               = Color.White,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Inline preview for [url].
 *   Image: a "Load preview" button; nothing downloads until tapped, and the result survives buffer
 *     switches.
 *   YouTube: the thumbnail loads automatically; play opens an inline player.
 *   Twitter/X: a thumbnail from api.fxtwitter.com; a host-validated video plays inline, otherwise
 *     tapping opens the tweet.
 * SVGs are blocked; downloads are capped at 5 MB with a MIME allow-list.
 */

/**
 * Displays an animated GIF using an [android.widget.ImageView].
 * On API 28+ uses [android.graphics.ImageDecoder] for hardware-accelerated decoding.
 * On API 26/27 falls back to [android.graphics.drawable.AnimationDrawable] via [android.graphics.Movie].
 */
@Composable
private fun AnimatedGif(bytes: ByteArray, modifier: Modifier = Modifier) {
    var drawable by remember(bytes) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(bytes) {
        drawable = withContext(Dispatchers.Default) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                    android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                        val scale = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
                        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                    }
                } else {
                    // Older devices show a bounded still frame instead of unbounded Movie decoding.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let {
                        android.graphics.drawable.BitmapDrawable(context.resources, it)
                    }
                }
            }.getOrNull()
        }
    }
    androidx.compose.runtime.DisposableEffect(drawable, lifecycle) {
        val animation = drawable as? android.graphics.drawable.Animatable
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) animation?.start()
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) animation?.stop()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) animation?.start()
        onDispose { animation?.stop(); lifecycle.removeObserver(observer) }
    }
    AndroidView(factory = { ctx -> android.widget.ImageView(ctx).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
    } }, update = { it.setImageDrawable(drawable) }, modifier = modifier)
}

/**
 * Drawable wrapper for [android.graphics.Movie] to animate GIFs on API < 28.
 * Movie is deprecated at API 29 but remains the only GIF animation option on API 26/27.
 */
@Suppress("DEPRECATION")
private class MovieDrawable(private val movie: android.graphics.Movie) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val startMs = android.os.SystemClock.uptimeMillis()

    override fun draw(canvas: android.graphics.Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        val relTime = ((now - startMs) % duration).toInt()
        movie.setTime(relTime)
        val scaleX = bounds.width().toFloat() / movie.width().coerceAtLeast(1)
        val scaleY = bounds.height().toFloat() / movie.height().coerceAtLeast(1)
        val scale = minOf(scaleX, scaleY)
        canvas.save()
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restore()
        invalidateSelf()  // request next frame
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in API 29")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

@Composable
fun InlinePreview(
    url: String,
    previewsEnabled: Boolean,
    wifiOnly: Boolean,
) {
    if (!previewsEnabled) return

    val onion = remember(url) { com.boxlabs.hexdroid.HttpPolicy.isOnion(url) }
    val transport = if (onion) LocalOrbotRemoteContent.current else LocalRemoteContent.current
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    val youtubeId   = remember(url) { if (onion) null else extractYouTubeId(url) }
    val twitterData = remember(url) { if (onion) null else extractTwitterData(url) }
    val isImage     = remember(url) { isPreviewableImageUrl(url) }

    if (youtubeId == null && twitterData == null && !isImage && !onion) return

    // Onion previews always use their dedicated Orbot route; Twitter/X requires a tap.
    // Twitter images can be sensitive or high-bandwidth, and the fxtwitter API
    // call leaks the URL to a third party — opt-in is the right default.
    val autoLoad = youtubeId != null || onion

    var state        by rememberSaveable(url, stateSaver = previewStateSaver) {
        mutableStateOf(if (autoLoad) PreviewState.Loading else PreviewState.Idle)
    }
    var loadRequested by rememberSaveable(url) { mutableStateOf(autoLoad) }
    // Not rememberSaveable: playing state must reset when scrolled away.
    var isPlaying     by remember(url) { mutableStateOf(false) }

    val previewLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(url, loadRequested, transport, previewLifecycle) {
      previewLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
        if (!loadRequested) return@repeatOnLifecycle
        if (state is PreviewState.Ready) return@repeatOnLifecycle
        if (wifiOnly && !isOnWifi(context)) {
            state = PreviewState.Failed(context.getString(R.string.img_wifi_only))
            return@repeatOnLifecycle
        }

        state = PreviewState.Loading
        val client = try { if (youtubeId != null) httpClient(context) else transport?.client() ?: throw java.io.IOException("Preview route unavailable") } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            state = PreviewState.Failed(context.getString(R.string.img_failed_load))
            return@repeatOnLifecycle
        }

        when {
            youtubeId != null -> {
                when (val result = fetchBitmap(youtubeThumbnailUrl(youtubeId), context, client)) {
                    is FetchResult.Success -> state = PreviewState.Ready(
                        bitmap = result.bitmap, isYouTube = true, videoId = youtubeId
                    )
                    FetchResult.TooLarge -> state = PreviewState.Failed(context.getString(R.string.img_too_large))
                    FetchResult.Error    -> state = PreviewState.Failed(context.getString(R.string.img_failed_load))
                }
            }
            twitterData != null -> {
                val meta = fetchTwitterMeta(twitterData, context, client)
                if (meta == null) {
                    // Text-only tweet or API failure — nothing to show.
                    state = PreviewState.Failed(context.getString(R.string.img_no_media))
                } else {
                    when (val result = fetchBitmap(meta.thumbnailUrl, context, client)) {
                        is FetchResult.Success -> state = PreviewState.Ready(
                            bitmap = result.bitmap,
                            isTwitterVideo = meta.hasVideo,
                            twitterUrl = meta.tweetUrl,
                            twitterVideoUrl = meta.videoUrl,
                        )
                        FetchResult.TooLarge -> state = PreviewState.Failed(context.getString(R.string.img_too_large))
                        FetchResult.Error    -> state = PreviewState.Failed(context.getString(R.string.img_failed_load))
                    }
                }
            }
            else -> {
                when (val result = fetchBitmap(url, context, client)) {
                    is FetchResult.Success -> state = PreviewState.Ready(
                        bitmap = result.bitmap,
                        rawBytes = result.rawBytes,
                        isGif = result.isGif,
                        pageTitle = result.pageTitle,
                    )
                    FetchResult.TooLarge   -> state = PreviewState.Failed(context.getString(R.string.img_too_large))
                    FetchResult.Error      -> state = PreviewState.Failed(context.getString(R.string.img_failed_load))
                }
            }
        }
    }
    }

    when (val s = state) {
        is PreviewState.Idle, is PreviewState.Failed -> {
            androidx.compose.material3.OutlinedButton(
                onClick = { loadRequested = true },
                modifier = Modifier.padding(top = 2.dp).focusHighlight(RoundedCornerShape(6.dp)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                androidx.compose.material3.Text(
                    text = when {
                        s is PreviewState.Failed -> s.message
                        else -> stringResource(R.string.img_load_preview)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        is PreviewState.Loading -> {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth(0.85f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }

        is PreviewState.Ready -> {
            val hasPlayOverlay = s.isYouTube || (s.isTwitterVideo && transport?.proxied != true)
            AnimatedContent(
                targetState    = isPlaying,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label          = "preview_toggle",
                modifier       = Modifier.padding(top = 4.dp).fillMaxWidth(0.85f),
            ) { playing ->
                if (playing && s.isYouTube) {
                    YouTubePlayer(
                        videoId = s.videoId,
                        onClose = { isPlaying = false },
                    )
                } else if (playing && transport?.proxied != true && s.isTwitterVideo && s.twitterVideoUrl != null) {
                    TwitterVideoPlayer(
                        videoUrl = s.twitterVideoUrl,
                        onClose = { isPlaying = false },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(when {
                                s.isYouTube -> Modifier.focusHighlight().clickable { isPlaying = true }
                                s.isTwitterVideo && transport?.proxied != true -> Modifier.focusHighlight().clickable {
                                    // Play inline when we have a trusted video URL; otherwise
                                    // fall back to opening the tweet in the browser.
                                    if (s.twitterVideoUrl != null) isPlaying = true
                                    else runCatching { uriHandler.openUri(s.twitterUrl) }
                                }
                                else -> Modifier
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (s.pageTitle != null) {
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(16.dp).padding(end = 28.dp)) {
                                androidx.compose.material3.Text(s.pageTitle, style = MaterialTheme.typography.titleSmall)
                                androidx.compose.material3.Text("Loaded through Orbot", style = MaterialTheme.typography.labelSmall)
                            }
                        } else if (s.isGif && s.rawBytes != null) {
                            AnimatedGif(
                                bytes = s.rawBytes,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            )
                        } else if (s.bitmap != null && !s.bitmap.isRecycled && s.bitmap.width > 0 && s.bitmap.height > 0) {
                            // A recycled or zero-sized bitmap throws when drawn, so draw nothing in
                            // that case.
                            Image(
                                bitmap             = s.bitmap.asImageBitmap(),
                                contentDescription = when {
                                    s.isYouTube      -> stringResource(R.string.img_youtube_thumb)
                                    s.isTwitterVideo -> stringResource(R.string.img_twitter_video_thumb)
                                    else             -> stringResource(R.string.img_preview)
                                },
                                contentScale = ContentScale.Crop,
                                modifier     = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            )
                        }
                        if (hasPlayOverlay) {
                            Icon(
                                imageVector        = Icons.Default.PlayCircleOutline,
                                contentDescription = if (s.isYouTube || s.twitterVideoUrl != null) stringResource(R.string.img_play_video) else stringResource(R.string.img_open_tweet_video),
                                tint               = Color.White,
                                modifier           = Modifier
                                    .size(64.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(32.dp)),
                            )
                        }
                        IconButton(
                            onClick = { state = PreviewState.Idle; loadRequested = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(28.dp)
                                .focusHighlight(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Close,
                                contentDescription = stringResource(R.string.img_dismiss_preview),
                                tint               = Color.White,
                                modifier           = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
