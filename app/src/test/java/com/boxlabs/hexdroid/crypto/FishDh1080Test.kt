package com.boxlabs.hexdroid.crypto

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class FishDh1080Test {
    @Test fun matchesIndependentDhAndSha256Vector() {
        // Independently calculated using Wraith's unpadded DH secret + SHA-256 encoding.
        val secret = BigInteger("123456789abcdef123456789abcdef123456789abcdef123456789abcdef1234", 16)
        val exchange = FishDh1080.Exchange(FishDh1080.unsigned(secret))
        val peer = "MXYYtWMvUNeiuw/pqvKUA/G27Q/2Ckws3QHelnNx2M+16FLgGrMTJq+RBWL7AhEGw1OTm+YszKPw3yDdh+HB6KUvAGIxtpgDc3830eGl6f26G8EJNyGuXAS5UL/8DeG6wFHBb0R+dxKGDQD2D1PqwX9F8XcB1wfk/2BgJfYTfTk7ml+qI7omA"
        assertEquals("IvsvFMWKiiwWUUqOVQI0xILSMDdjgSXqMWeukyOfwSI", exchange.finish(peer))
        exchange.destroy()
    }
    @Test fun peersAgreeAndExpiredExchangeCannotBeReused() {
        val a = FishDh1080.create(); val b = FishDh1080.create()
        assertEquals(a.finish(b.publicKey), b.finish(a.publicKey))
        a.destroy()
        assertThrows(IllegalArgumentException::class.java) { a.finish(b.publicKey) }
        b.destroy()
    }
    @Test fun rejectsInvalidAndSmallSubgroupPublicKeys() {
        val a = FishDh1080.create()
        for (n in listOf(BigInteger.ZERO, BigInteger.ONE, FishDh1080.prime.subtract(BigInteger.ONE), FishDh1080.prime)) {
            assertThrows(IllegalArgumentException::class.java) { a.finish(FishDh1080.encode(FishDh1080.unsigned(n))) }
        }
        for (value in listOf("!", "A".repeat(182), "AA==", "not a key", a.publicKey + " CBC"))
            assertThrows(IllegalArgumentException::class.java) { a.finish(value) }
        a.destroy()
    }
    @Test fun fishEncodingHandlesTerminatorAndPadding() {
        assertEquals("AQIDA", FishDh1080.encode(byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), FishDh1080.decode("AQIDA"))
        assertEquals("AQI", FishDh1080.encode(byteArrayOf(1, 2)))
    }
    @Test fun legacyEcbMatchesOpenSslWraithEncoding() {
        val cipher = BlowfishCipher("wraith-test-key".toByteArray(), useEcb = true)
        val wire = "+OK VkxVK/sYC1y1Thgcg0bX8RH."
        assertEquals(wire, cipher.encrypt("hello Wraith", ""))
        assertEquals("hello Wraith", cipher.decrypt(wire, ""))
    }
}
