package ru.asop.payment.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Промпт 016 §1.1: подпись локального API app-payment — hex(HMAC_SHA256(secret, timestamp)).
 * Тот же helper, что `CertSignHmacInterceptor` в android-terminal (окно 300 c).
 */
object Hmac {

    private val HEX = "0123456789abcdef".toCharArray()

    fun sign(secret: String, timestampMillis: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(timestampMillis.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}