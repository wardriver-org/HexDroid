package com.boxlabs.hexdroid

import java.io.InputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Configurable multipart uploader. No IRC credentials are accepted or sent. */
internal object MultipartUploader {

    fun upload(config: UploaderConfig, fileName: String?, mimeType: String?, input: InputStream, cacheDir: File): FilehostUpload.Result {
        config.validate()?.let { return FilehostUpload.Result(null, it) }
        val boundary = "HexDroid-${UUID.randomUUID()}"
        var mime = mimeType?.takeIf { it.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) }
            ?: "application/octet-stream"
        var conn: HttpURLConnection? = null
        val staged = mutableListOf<File>()
        var uploadName = fileName ?: "file"
        return try {
            // drop.fo rejects chunked multipart requests. Stage the selected file privately
            // so cloud providers with unknown/stale sizes still get an exact Content-Length.
            val original = File.createTempFile("upload-", ".upload", cacheDir)
            staged.add(original)
            original.outputStream().use { input.copyTo(it, 64 * 1024) }
            val clean = UploadPreparation.sanitize(original, uploadName, mimeType, cacheDir)
            if (clean.file != original) staged.add(clean.file)
            var file = clean.file
            uploadName = clean.name
            mime = clean.mime
            if (config.ageEnabled) {
                go.agebridge.Agebridge.validateRecipients(config.ageRecipients)
                val encrypted = File(cacheDir, "encrypted-${UUID.randomUUID()}.age")
                staged.add(encrypted)
                go.agebridge.Agebridge.encryptFile(file.path, encrypted.path, config.ageRecipients)
                file = encrypted
                uploadName = "attachment.age"
                mime = "application/octet-stream"
            }
            val fields = when (config.provider) {
                UploadProvider.CATBOX -> mapOf("reqtype" to "fileupload")
                UploadProvider.LITTERBOX -> mapOf("reqtype" to "fileupload", "time" to "24h")
                else -> emptyMap()
            }
            val prefix = fields.entries.joinToString("") { (key, value) ->
                "--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n"
            }
            val header = (prefix + "--$boundary\r\nContent-Disposition: form-data; name=\"${config.field}\"; filename=\"${FilehostUpload.sanitizeFileName(uploadName)}\"\r\nContent-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8)
            val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
            conn = URL(config.endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Accept", "text/plain, application/json")
            conn.setRequestProperty("User-Agent", "HexDroid/1.7.6")
            config.authorization?.takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("Authorization", it) }
            conn.setFixedLengthStreamingMode(header.size.toLong() + file.length() + footer.size)
            conn.outputStream.use { out ->
                out.write(header)
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.write(footer)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val detail = conn.errorStream?.use { readBounded(it, 512) }
                    .orEmpty().replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(200)
                FilehostUpload.Result(null, "Upload failed (HTTP $code)${if (detail.isEmpty()) "" else ": $detail"}")
            } else {
                val body = conn.inputStream.use { readBounded(it, 4096) }.trim()
                val value = when (config.response) {
                    UploadResponse.TEXT_URL -> body
                    UploadResponse.LOCATION -> conn.getHeaderField("Location")?.let { URL(URL(config.endpoint), it).toString() }.orEmpty()
                    UploadResponse.JSON_URL -> {
                        var node: Any = org.json.JSONObject(body)
                        config.jsonKey.split('.').forEach { key -> node = (node as org.json.JSONObject).get(key) }
                        node as? String ?: ""
                    }
                }
                parseResponse(value, config.allowHttp)
            }
        } catch (e: Exception) {
            FilehostUpload.Result(null, "Upload failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
            staged.forEach { it.delete() }
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
