package com.boxlabs.hexdroid

import java.io.InputStream
import java.io.File
import com.boxlabs.hexdroid.connection.ProxyConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.concurrent.TimeUnit
import java.net.URL
import java.util.UUID

/** Configurable multipart uploader. No IRC credentials are accepted or sent. */
internal object MultipartUploader {

    fun upload(config: UploaderConfig, fileName: String?, mimeType: String?, input: InputStream, cacheDir: File, proxy: ProxyConfig, checkCancelled: () -> Unit = {}): FilehostUpload.Result {
        config.validate()?.let { return FilehostUpload.Result(null, it) }
        var mime = mimeType?.takeIf { it.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) }
            ?: "application/octet-stream"
        val staged = mutableListOf<File>()
        var uploadName = fileName ?: "file"
        return try {
            // drop.fo rejects chunked multipart requests. Stage the selected file privately
            // so cloud providers with unknown/stale sizes still get an exact Content-Length.
            val original = File.createTempFile("upload-", ".upload", cacheDir)
            staged.add(original)
            original.outputStream().use { out ->
                UploadLimits.copy(input, out, { cacheDir.usableSpace }, checkCancelled)
            }
            checkCancelled()
            val clean = UploadPreparation.sanitize(original, uploadName, mimeType, cacheDir)
            if (clean.file != original) staged.add(clean.file)
            var file = clean.file
            uploadName = clean.name
            mime = clean.mime
            if (file.length() > UploadLimits.MAX_BYTES) throw java.io.IOException("Prepared file exceeds 64 MiB upload limit")
            checkCancelled()
            if (config.ageEnabled) {
                if (cacheDir.usableSpace < file.length() + UploadLimits.RESERVE_BYTES)
                    throw java.io.IOException("Not enough space to encrypt this upload")
                go.agebridge.Agebridge.validateRecipients(config.ageRecipients)
                val encrypted = File(cacheDir, "encrypted-${UUID.randomUUID()}.age")
                staged.add(encrypted)
                go.agebridge.Agebridge.encryptFile(file.path, encrypted.path, config.ageRecipients)
                file = encrypted
                uploadName = "attachment.age"
                mime = "application/octet-stream"
            }
            checkCancelled()
            uploadPrepared(config, file, uploadName, mime, proxy)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FilehostUpload.Result(null, if (config.useOrbot) "Tor upload failed. Check that Orbot is running on 127.0.0.1:9050: ${e.message ?: e.javaClass.simpleName}"
                else "Upload failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            staged.forEach { it.delete() }
        }
    }

    /** Send only a sanitized/encrypted staged file; fixed length works with drop.fo. */
    internal fun uploadPrepared(config: UploaderConfig, file: File, name: String, mime: String,
                                proxy: ProxyConfig): FilehostUpload.Result {
        config.validate()?.let { return FilehostUpload.Result(null, it) }
        val client = RemoteContentHttp.client(config.uploadProxy(proxy), allowHttpEndpoint = config.endpoint.takeIf { config.allowHttp }).newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                when (config.provider) {
                    UploadProvider.CATBOX -> addFormDataPart("reqtype", "fileupload")
                    UploadProvider.LITTERBOX -> {
                        addFormDataPart("reqtype", "fileupload")
                        addFormDataPart("time", "24h")
                    }
                    else -> Unit
                }
                addFormDataPart(config.field, FilehostUpload.sanitizeFileName(name), file.asRequestBody(mime.toMediaType()))
            }.build()
            val request = Request.Builder().url(config.endpoint).post(body)
                .header("Accept", "text/plain, application/json")
                .header("User-Agent", "HexDroid/1.7.6")
                .apply { config.authorization?.takeIf { it.isNotEmpty() }?.let { header("Authorization", it) } }
                .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body.byteStream().use { readBounded(it, 512) }
                        .replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(200)
                    FilehostUpload.Result(null, "Upload failed (HTTP ${response.code})${if (detail.isEmpty()) "" else ": $detail"}")
                } else {
                    val text = response.body.byteStream().use { readBounded(it, 4096) }.trim()
                    val value = when (config.response) {
                        UploadResponse.TEXT_URL -> text
                        UploadResponse.LOCATION -> response.header("Location")?.let { URL(URL(config.endpoint), it).toString() }.orEmpty()
                        UploadResponse.JSON_URL -> {
                            var node: Any = org.json.JSONObject(text)
                            config.jsonKey.split('.').forEach { key -> node = (node as org.json.JSONObject).get(key) }
                            node as? String ?: ""
                        }
                    }
                    parseResponse(value, config.allowHttp)
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun readBounded(input: InputStream, limit: Int): String {
        val bytes = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val n = input.read(bytes, count, limit - count)
            if (n < 0) break
            count += n
        }
        return String(bytes, 0, count, Charsets.UTF_8)
    }

    /** Only accept a single web link safe to append to an IRC draft. */
    internal fun parseResponse(body: String, allowHttp: Boolean = false): FilehostUpload.Result {
        val url = runCatching { java.net.URI(body.trim()) }.getOrNull()
        return if (url != null && (url.scheme == "https" || (allowHttp && url.scheme == "http")) && !url.host.isNullOrBlank()
            && url.rawUserInfo == null
            && !url.rawPath.isNullOrBlank() && url.rawPath != "/") {
            FilehostUpload.Result(url.toASCIIString(), null)
        } else FilehostUpload.Result(null, "Server returned an invalid upload URL")
    }
}
