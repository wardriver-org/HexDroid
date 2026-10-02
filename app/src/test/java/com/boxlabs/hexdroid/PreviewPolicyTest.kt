package com.boxlabs.hexdroid

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.io.ByteArrayInputStream
import java.io.IOException

class PreviewPolicyTest {
    @Test fun privateAndAmbiguousUrlsAreRejected() {
        for (url in listOf("https://127.0.0.1/a", "https://2130706433/a", "https://10.1.100.1/", "https://192.168.1.1/",
            "https://[::1]/", "https://[fc00::1]/", "https://[::ffff:127.0.0.1]/", "https://server.local/",
            "https://localhost/", "https://server/", "https://user:secret@example.com/", "https://example.com:22/",
            "http://example.com/image.png", "file:///private.png", "https://short.onion/")) {
            assertFalse(url, PreviewPolicy.allowedUrl(url))
        }
        assertTrue(PreviewPolicy.allowedUrl("https://example.com/image.png"))
        assertTrue(PreviewPolicy.allowedUrl("https://8.8.8.8/image.png"))
        assertTrue(PreviewPolicy.allowedUrl("http://${"a".repeat(56)}.onion/image"))
    }

    @Test fun resolvedAddressesAreCheckedBeforeDirectConnections() {
        for (ip in listOf("0.0.0.0", "127.0.0.1", "169.254.169.254", "100.64.0.1", "172.16.0.1",
            "192.168.1.1", "224.0.0.1", "198.18.0.1", "::1", "fd00::1", "fe80::1", "2001:db8::1")) {
            assertFalse(ip, PreviewPolicy.publicAddress(InetAddress.getByName(ip)))
        }
        assertTrue(PreviewPolicy.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(PreviewPolicy.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun bodyCapDoesNotTrustContentLength() {
        assertArrayEquals(byteArrayOf(1,2), PreviewPolicy.readBounded(ByteArrayInputStream(byteArrayOf(1,2)), 2))
        try {
            PreviewPolicy.readBounded(ByteArrayInputStream(ByteArray(11)), 10)
            fail("Oversized response accepted")
        } catch (_: IOException) { }
    }
}
