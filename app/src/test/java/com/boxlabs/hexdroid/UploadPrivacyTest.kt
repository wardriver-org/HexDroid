package com.boxlabs.hexdroid

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class UploadPrivacyTest {
    @Test fun onionDetectionUsesTheHostNotTextInTheUrl() {
        assertTrue(HttpPolicy.isOnion("http://example.onion/photo"))
        assertTrue(HttpPolicy.isOnion("https://EXAMPLE.ONION./"))
        assertFalse(HttpPolicy.isOnion("https://example.onion.evil.test/photo"))
        assertFalse(HttpPolicy.isOnion("https://evil.test/?url=http://example.onion/"))
        assertFalse(HttpPolicy.isOnion("http://example.onion@evil.test/"))
        assertFalse(HttpPolicy.isOnion("file://example.onion/photo"))
    }

    @Test fun redirectsCannotChangeOriginOrDowngradeTls() {
        assertTrue(HttpPolicy.sameOrigin("https://example.test/a", "https://example.test:443/b"))
        assertFalse(HttpPolicy.sameOrigin("https://example.test/a", "http://example.test/a"))
        assertFalse(HttpPolicy.sameOrigin("https://example.test/a", "https://other.test/a"))
        assertFalse(HttpPolicy.sameOrigin("https://example.test/a", "https://example.test:444/a"))
        assertFalse(HttpPolicy.sameOrigin("https://example.test/a", "https://secret@example.test/a"))
    }

    @Test fun boundedStagingCopiesExactBytes() {
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        UploadLimits.copy(ByteArrayInputStream(bytes), out, { Long.MAX_VALUE })
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test fun oversizedUnknownLengthStreamStopsBeforeWritingPastLimit() {
        val out = ByteArrayOutputStream()
        try {
            UploadLimits.copy(ByteArrayInputStream(ByteArray(20)), out, { Long.MAX_VALUE }, limit = 10)
            fail("Oversized input accepted")
        } catch (_: IOException) { assertTrue(out.size() <= 10) }
    }

    @Test fun lowStorageAndCancellationStopBeforeWriting() {
        val out = ByteArrayOutputStream()
        try {
            UploadLimits.copy(ByteArrayInputStream(byteArrayOf(1)), out, { 0L })
            fail("Low storage accepted")
        } catch (_: IOException) { assertEquals(0, out.size()) }
        try {
            UploadLimits.copy(ByteArrayInputStream(byteArrayOf(1)), out, { Long.MAX_VALUE },
                { throw java.util.concurrent.CancellationException() })
            fail("Cancellation ignored")
        } catch (_: java.util.concurrent.CancellationException) { assertEquals(0, out.size()) }
    }
}
