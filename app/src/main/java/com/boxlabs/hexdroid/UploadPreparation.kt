package com.boxlabs.hexdroid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/** Re-encode pixels into a clean PNG, removing EXIF/XMP/IPTC rather than relying on tag lists. */
internal object UploadPreparation {
    data class Prepared(val file: File, val name: String, val mime: String)

    fun sanitize(source: File, name: String, declaredMime: String?, cacheDir: File): Prepared {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val prefix = ByteArray(16)
        source.inputStream().use { it.read(prefix) }
        val ascii = String(prefix, Charsets.ISO_8859_1)
        val magicImage = (prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte()) ||
            ascii.startsWith("\u0089PNG") || ascii.startsWith("GIF8") || ascii.startsWith("BM") ||
            ascii.startsWith("II*\u0000") || ascii.startsWith("MM\u0000*") ||
            (ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WEBP") ||
            (ascii.substring(4, 8) == "ftyp" && ascii.substring(8, 12) in setOf("avif", "avis", "heic", "heix", "heif", "mif1", "msf1"))
        val image = magicImage || declaredMime?.startsWith("image/", true) == true || bounds.outMimeType?.startsWith("image/") == true ||
            name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp", "tif", "tiff", "svg", "raw", "dng", "cr2", "nef", "arw")
        if (!image) return Prepared(source, name, declaredMime ?: "application/octet-stream")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 16_000_000L) {
            throw IOException("Image cannot be safely sanitized (unsupported format or over 16 megapixels)")
        }
        val orientation = runCatching { ExifInterface(source).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrElse { throw IOException("Cannot read image metadata; upload stopped", it) }
        val bitmap = BitmapFactory.decodeFile(source.path) ?: throw IOException("Cannot decode image; upload stopped")
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        var rotated: Bitmap? = null
        val output = File.createTempFile("clean-image-", ".png", cacheDir)
        try {
            val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
            rotated = cleanBitmap
            output.outputStream().use { raw ->
                val bounded = object : java.io.FilterOutputStream(raw) {
                    var count = 0L
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        count += len
                        if (count > UploadLimits.MAX_BYTES || cacheDir.usableSpace < len + UploadLimits.RESERVE_BYTES)
                            throw IOException("Not enough space or sanitized image exceeds upload limit")
                        out.write(b, off, len)
                    }
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                }
                if (!cleanBitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded)) throw IOException("Image sanitization failed")
            }
            return Prepared(output, name.substringBeforeLast('.', name) + ".png", "image/png")
        } catch (e: Exception) { output.delete(); throw e }
        finally { if (rotated !== bitmap) rotated?.recycle(); bitmap.recycle() }
    }
}
