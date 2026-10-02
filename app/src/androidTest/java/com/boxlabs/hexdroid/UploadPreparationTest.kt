package com.boxlabs.hexdroid

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UploadPreparationTest {
    @Test fun removesGpsCameraAndOrientationMetadata() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File.createTempFile("metadata-test", ".jpg", dir)
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(source).apply {
            setLatLong(34.0, -118.0)
            setAttribute(ExifInterface.TAG_MAKE, "Test Camera")
            setAttribute(ExifInterface.TAG_USER_COMMENT, "Private note")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val clean = UploadPreparation.sanitize(source, "photo.jpg", "image/jpeg", dir)
        try {
            assertEquals("image/png", clean.mime)
            val exif = ExifInterface(clean.file)
            assertNull(exif.latLong)
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
            assertNull(exif.getAttribute(ExifInterface.TAG_USER_COMMENT))
            val decoded = android.graphics.BitmapFactory.decodeFile(clean.file.path)
            assertEquals(4, decoded.width)
            assertEquals(8, decoded.height)
            decoded.recycle()
        } finally { source.delete(); clean.file.delete() }
    }
}
