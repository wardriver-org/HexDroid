package com.boxlabs.hexdroid

import java.net.URI

internal object HttpPolicy {
    fun isOnion(url: String): Boolean = runCatching {
        val u = URI(url)
        u.scheme?.lowercase() in setOf("http", "https") && u.rawUserInfo == null &&
            u.host?.lowercase()?.trimEnd('.')?.endsWith(".onion") == true
    }.getOrDefault(false)

    fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = URI(first); val b = URI(second)
        fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
        a.scheme.equals(b.scheme, true) && a.host != null && a.host.equals(b.host, true) &&
            port(a) == port(b) && b.rawUserInfo == null
    }.getOrDefault(false)
}
