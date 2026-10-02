package com.boxlabs.hexdroid

/** Compatibility response parser for drop.fo-specific tests. */
internal object DropFoUpload {
    internal fun parseResponse(body: String): FilehostUpload.Result {
        val result = MultipartUploader.parseResponse(body)
        val url = result.url?.let { java.net.URI(it) }
        return if (url != null && url.host.equals("drop.fo", true) && (url.port == -1 || url.port == 443)) result
            else FilehostUpload.Result(null, "drop.fo returned an invalid upload URL")
    }
}
