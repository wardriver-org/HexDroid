package com.boxlabs.hexdroid

import org.junit.Assert.*
import org.junit.Test

class DropFoUploadTest {
    @Test fun acceptsDropFoLinks() {
        assertEquals("https://drop.fo/abc.txt", DropFoUpload.parseResponse("https://drop.fo/abc.txt\n").url)
    }
    @Test fun rejectsUnsafeOrUnexpectedResponses() {
        listOf("http://drop.fo/abc", "https://evil.example/abc", "https://drop.fo/",
            "https://user@drop.fo/abc", "https://drop.fo:444/abc", "<html>Error</html>",
            "https://drop.fo/abc\nPRIVMSG #channel :oops").forEach {
            assertFalse(it, DropFoUpload.parseResponse(it).ok)
        }
    }
}
