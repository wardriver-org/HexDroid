package com.boxlabs.hexdroid

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI

/** Applied only to untrusted previews, not user-configured upload destinations. */
internal object PreviewPolicy {
    fun allowedUrl(value: String): Boolean = runCatching {
        if (value.length > 4096) return false
        val uri = URI(value)
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.') ?: return false
        if (uri.rawUserInfo != null || uri.port !in listOf(-1, 80, 443)) return false
        if (HttpPolicy.isOnion(value)) {
            val address = host.removeSuffix(".onion").substringAfterLast('.')
            return address.matches(Regex("[a-z2-7]{56}"))
        }
        if (!uri.scheme.equals("https", true) || !host.contains('.') && !host.contains(':')) return false
        if (listOf("localhost", "local", "internal", "lan", "home").any { host == it || host.endsWith(".$it") }) return false
        if ('%' in host) return false
        if (':' in host || host.split('.').all { it.matches(Regex("(?i)(0x[0-9a-f]+|[0-9]+)")) }) {
            return publicAddress(InetAddress.getByName(host))
        }
        true
    }.getOrDefault(false)

    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            return !(b[0] == 0 || b[0] >= 224 || b[0] == 127 || b[0] == 10 ||
                (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
                (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 192 && b[1] == 0) || (b[0] == 198 && b[1] in 18..19) ||
                (b[0] == 198 && b[1] == 51 && b[2] == 100) ||
                (b[0] == 203 && b[1] == 0 && b[2] == 113))
        }
        // Global unicast only; excludes ULA, scoped/link-local, mapped and transition ranges.
        return b.size == 16 && b[0] and 0xe0 == 0x20 &&
            !(b[0] == 0x20 && b[1] == 0x02) &&
            !(b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8)))
    }

    /** Enforced on decompressed bytes too, regardless of Content-Length or gzip. */
    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (n < 0) return output.toByteArray()
            if (output.size() + n > limit) throw IOException("Preview exceeds size limit")
            output.write(buffer, 0, n)
        }
    }
}
