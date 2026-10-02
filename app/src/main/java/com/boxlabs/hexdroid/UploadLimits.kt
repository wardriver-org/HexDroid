package com.boxlabs.hexdroid

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

internal object UploadLimits {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val RESERVE_BYTES = 16L * 1024 * 1024
    fun copy(input: InputStream, output: OutputStream, freeSpace: () -> Long,
             checkCancelled: () -> Unit = {}, limit: Long = MAX_BYTES) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Upload exceeds 64 MiB limit")
            if (freeSpace() < count + RESERVE_BYTES) throw IOException("Not enough space to prepare upload")
            output.write(buffer, 0, count)
        }
    }
}
