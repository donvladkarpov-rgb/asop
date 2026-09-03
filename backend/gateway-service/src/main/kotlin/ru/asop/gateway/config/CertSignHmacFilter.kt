package ru.asop.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 authentication filter for the cert-sign endpoint (POST /api/v1/terminals/cert-sign).
 * This endpoint is open (no JWT, no mTLS — chicken-and-egg), so HMAC prevents unauthorized cert requests.
 *
 * Validates: X-API-Key + X-Timestamp + HMAC-SHA256(timestamp, secret).
 * Rate limited per key.
 */
@Component
class CertSignHmacFilter(
    @Value("\${asop.cert-sign-api-keys:}") private val apiKeysRaw: String = ""
) : WebFilter {

    private val log = LoggerFactory.getLogger(javaClass)
    private val keyCache = ConcurrentHashMap<String, ApiKeyRecord>()
    private val rateLimiters = ConcurrentHashMap<String, SlidingWindowCounter>()

    data class ApiKeyRecord(val keyValue: String, val hmacSecret: String, val rateLimit: Int)

    class SlidingWindowCounter(private val limit: Int) {
        private val timestamps = mutableListOf<Long>()

        @Synchronized
        fun allow(): Boolean {
            val now = System.currentTimeMillis()
            timestamps.removeAll { it < now - 60_000 }
            if (timestamps.size >= limit) return false
            timestamps.add(now)
            return true
        }
    }

    init {
        parseKeys()
    }

    private fun parseKeys() {
        if (apiKeysRaw.isBlank()) {
            log.warn("No cert-sign API keys configured — all cert-sign requests will be rejected")
            return
        }
        apiKeysRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
            val parts = entry.split(":")
            if (parts.size >= 2) {
                val key = parts[0].trim()
                val hmac = parts[1].trim()
                val rateLimit = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 10
                keyCache[key] = ApiKeyRecord(key, hmac, rateLimit)
                log.info("Cert-sign API key loaded: {}...", key.take(15))
            }
        }
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        val method = exchange.request.method
        if (path != "/api/v1/terminals/cert-sign" || method != HttpMethod.POST) {
            return chain.filter(exchange)
        }

        val apiKey = exchange.request.headers.getFirst("X-API-Key")
        if (apiKey.isNullOrBlank()) {
            log.warn("Cert-sign: missing X-API-Key header")
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }

        val record = keyCache[apiKey]
        if (record == null) {
            log.warn("Cert-sign: invalid API key: {}...", apiKey.take(10))
            exchange.response.statusCode = HttpStatus.FORBIDDEN
            return exchange.response.setComplete()
        }

        val limiter = rateLimiters.computeIfAbsent(record.keyValue) { SlidingWindowCounter(record.rateLimit) }
        if (!limiter.allow()) {
            log.warn("Cert-sign: rate limit exceeded for key: {}...", record.keyValue.take(10))
            exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
            return exchange.response.setComplete()
        }

        val timestamp = exchange.request.headers.getFirst("X-Timestamp")
        val signature = exchange.request.headers.getFirst("X-Signature")

        if (timestamp.isNullOrBlank() || signature.isNullOrBlank()) {
            log.warn("Cert-sign: missing HMAC headers (X-Timestamp/X-Signature) for key: {}...", record.keyValue.take(10))
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }

        val now = Instant.now().epochSecond
        val ts = timestamp.toLongOrNull() ?: 0L
        if (kotlin.math.abs(now - ts) > 300) {
            log.warn("Cert-sign: HMAC timestamp expired: {} vs now {}", ts, now)
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }

        // HMAC(timestamp, secret) — simple, no body needed
        val expectedSig = hmacSha256(timestamp, record.hmacSecret)
        if (!constantTimeEquals(expectedSig, signature)) {
            log.warn("Cert-sign: HMAC mismatch for key: {}...", record.keyValue.take(10))
            exchange.response.statusCode = HttpStatus.FORBIDDEN
            return exchange.response.setComplete()
        }

        return chain.filter(exchange)
    }

    private fun hmacSha256(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
