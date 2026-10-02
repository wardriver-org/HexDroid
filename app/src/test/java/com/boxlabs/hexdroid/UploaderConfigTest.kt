package com.boxlabs.hexdroid

import org.junit.Assert.*
import org.junit.Test

class UploaderConfigTest {
    @Test fun dropFoTorIsOptInAndOverridesOnlyUploadRoute() {
        val network = com.boxlabs.hexdroid.connection.ProxyConfig(
            com.boxlabs.hexdroid.connection.ProxyType.SOCKS4A, "irc-proxy.example", 1080, "irc-user", "irc-pass")
        val settings = UiSettings()
        assertFalse(settings.uploadDropFoTor)
        assertEquals(network, settings.uploadConfig().uploadProxy(network))
        val tor = settings.copy(uploadDropFoTor = true).uploadConfig()
        assertEquals(DROPFO_ONION, tor.endpoint)
        assertTrue(tor.allowHttp)
        assertNull(tor.validate())
        val proxy = tor.uploadProxy(network)
        assertEquals(com.boxlabs.hexdroid.connection.ProxyType.SOCKS5, proxy.type)
        assertEquals("127.0.0.1", proxy.host)
        assertEquals(9050, proxy.port)
        assertEquals(com.boxlabs.hexdroid.connection.ProxyConfig(
            com.boxlabs.hexdroid.connection.ProxyType.SOCKS5, "127.0.0.1", 9050), proxy)
        assertEquals(network, settings.copy(uploadDropFoTor = true, uploadProvider = UploadProvider.ZEROXZERO).uploadConfig().uploadProxy(network))
        assertNotNull(tor.copy(endpoint = "https://drop.fo/").validate())
        assertNotNull(tor.copy(provider = UploadProvider.CUSTOM).validate())
    }

    @Test fun uploadsAreOptInAndDropIsOnlyPreselected() {
        val settings = UiSettings()
        assertFalse(settings.uploadsEnabled)
        assertEquals(UploadProvider.DROPFO, settings.uploadProvider)
        assertEquals("https://drop.fo/", settings.copy(uploadsEnabled = true).uploadConfig().endpoint)
    }
    @Test fun providerMappingsAndSelectionPersistence() {
        val cat = UiSettings(uploadProvider = UploadProvider.CATBOX).uploadConfig()
        assertEquals("fileToUpload", cat.field)
        assertEquals("https://catbox.moe/user/api.php", cat.endpoint)
        val selfHosted = UiSettings(uploadProvider = UploadProvider.RUSTYPASTE, uploadEndpoint = "https://paste.example/")
        assertEquals("secret", selfHosted.uploadConfig("secret").authorization)
        assertEquals(UploadProvider.RUSTYPASTE, selfHosted.copy(uploadsEnabled = false).copy(uploadsEnabled = true).uploadProvider)
        assertNull(UiSettings(uploadProvider = UploadProvider.ZEROXZERO).uploadConfig("secret").authorization)
    }
    @Test fun invalidEndpointsAndHeaderInjectionAreRejected() {
        val config = UploaderConfig(UploadProvider.CUSTOM, "https://paste.example/")
        assertNull(config.validate())
        assertNotNull(config.copy(endpoint = "file:///tmp/paste").validate())
        assertNotNull(config.copy(endpoint = "https://user:pass@paste.example/").validate())
        assertNotNull(config.copy(endpoint = "http://paste.example/").validate())
        assertNull(config.copy(endpoint = "http://paste.example:8000/", allowHttp = true).validate())
        assertNotNull(config.copy(field = "file\r\nInjected").validate())
        assertNotNull(config.copy(authorization = "token\r\nInjected: yes").validate())
    }
    @Test fun responseUrlsAreSingleSafeWebLinks() {
        assertTrue(MultipartUploader.parseResponse("https://cdn.example/file.png\n").ok)
        assertFalse(MultipartUploader.parseResponse("javascript:alert(1)").ok)
        assertFalse(MultipartUploader.parseResponse("http://paste.example/file").ok)
        assertTrue(MultipartUploader.parseResponse("http://paste.example:8000/file", true).ok)
        assertFalse(MultipartUploader.parseResponse("https://paste.example/file\nPRIVMSG #chat :text").ok)
    }
}
