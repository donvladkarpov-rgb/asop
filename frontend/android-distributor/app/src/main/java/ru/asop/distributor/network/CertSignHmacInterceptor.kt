package ru.asop.distributor.network

import okhttp3.Interceptor
import okhttp3.Response
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 для cert-sign (порт из терминала): X-API-Key / X-Timestamp / X-Signature.
 */
class CertSignHmacInterceptor(
    private val apiKey: String,
    private val hmacSecret: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val timestamp = Instant.now().epochSecond.toString()
        val signature = try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(hmacSecret.toByteArray(), "HmacSHA256"))
            mac.doFinal(timestamp.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
        val request = chain.request().newBuilder()
            .addHeader("X-API-Key", apiKey)
            .addHeader("X-Timestamp", timestamp)
            .apply { if (signature.isNotEmpty()) addHeader("X-Signature", signature) }
            .build()
        return chain.proceed(request)
    }
}