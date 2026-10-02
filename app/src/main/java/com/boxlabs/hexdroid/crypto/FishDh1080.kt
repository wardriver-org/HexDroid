package com.boxlabs.hexdroid.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Legacy FiSH DH1080 / Blowfish-ECB interoperability with Wraith; not authenticated DH. */
internal object FishDh1080 {
    internal val prime = BigInteger("FBE1022E23D213E8ACFA9AE8B9DFADA3EA6B7AC7A7B7E95AB5EB2DF858921FEADE95E6AC7BE7DE6ADBAB8A783E7AF7A7FA6A2B7BEB1E72EAE2B72F9FA2BFB2A2EFBEFAC868BADB3E828FA8BADFADA3E4CC1BE7E8AFE85E9698A783EB68FA07A77AB6AD7BEB618ACF9CA2897EB28A6189EFA07AB99A8A7FA9AE299EFA7BA66DEAFEFBEFBF0B7D8B", 16)
    private val two = BigInteger.valueOf(2)
    class Exchange internal constructor(private val secret: ByteArray) {
        val publicKey: String = encode(unsigned(two.modPow(BigInteger(1, secret), prime)))
        fun finish(peer: String): String {
            require(secret.any { it != 0.toByte() }) { "Expired key exchange" }
            val value = BigInteger(1, decode(peer))
            require(value >= two && value <= prime.subtract(two)) { "Invalid DH1080 public key" }
            val shared = unsigned(value.modPow(BigInteger(1, secret), prime))
            return try { encode(MessageDigest.getInstance("SHA-256").digest(shared)) }
            finally { shared.fill(0) }
        }
        fun destroy() { secret.fill(0) }
    }
    fun create(): Exchange {
        val secret = ByteArray(32)
        do { SecureRandom().nextBytes(secret) } while (BigInteger(1, secret) < two)
        return Exchange(secret)
    }
    internal fun unsigned(n: BigInteger): ByteArray = n.toByteArray().let {
        if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
    }
    internal fun encode(bytes: ByteArray): String {
        val value = Base64.getEncoder().encodeToString(bytes)
        return if ('=' in value) value.trimEnd('=') else value + "A"
    }
    internal fun decode(value: String): ByteArray {
        require(value.length in 2..181 && value.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/" })
        val raw = if (value.length % 4 == 1 && value.endsWith('A')) value.dropLast(1) else value
        require(raw.length % 4 != 1)
        val bytes = Base64.getDecoder().decode(raw)
        require(bytes.size in 1..135 && encode(bytes) == value) { "Invalid FiSH encoding" }
        return bytes
    }
}
