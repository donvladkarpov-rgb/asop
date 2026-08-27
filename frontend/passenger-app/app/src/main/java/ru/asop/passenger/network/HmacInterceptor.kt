package ru.asop.passenger.network

import okhttp3.Interceptor
import okhttp3.Response
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacInterceptor(
    private val apiKey: String,
    private val hmacSecret: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val body = chain.request().body?.toString() ?: ""
        val timestamp = Instant.now().epochSecond.toString()
        val payload = body + timestamp
        val signature = try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(hmacSecret.toByteArray(), "HmacSHA256"))
            mac.doFinal(payload.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }

        val request = chain.request().newBuilder()
            .addHeader("X-API-Key", apiKey)
            .addHeader("X-Timestamp", timestamp)
            .apply { if (signature.isNotEmpty()) addHeader("X-Signature", signature) }
            .build()
        return chain.proceed(request)
    }
}
