package ru.asop.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
 * API key authentication filter for public passenger endpoints.
 * Validates X-API-Key header + optional HMAC-SHA256 signature.
 * Falls back to plain key match if X-Signature is absent.
 *
 * Keys are configured via application.yml (asop.api-keys list) — no DB access from gateway.
 */
@Component
class ApiKeyHmacFilter(
    @Value("\${asop.api-keys:}") private val apiKeysRaw: String = ""
) : WebFilter {

    private val log = LoggerFactory.getLogger(javaClass)
    private val keyCache = ConcurrentHashMap<String, ApiKeyRecord>()
    private val rateLimiters = ConcurrentHashMap<String, SlidingWindowCounter>()

    data class ApiKeyRecord(
        val keyValue: String,
        val hmacSecret: String,
        val rateLimit: Int,
    )

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
        // Format: "key1:hmac1,key2:hmac2" or empty
        if (apiKeysRaw.isBlank()) {
            log.info("No API keys configured — all public requests will be rejected")
            return
        }
        apiKeysRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
            val parts = entry.split(":")
            if (parts.size >= 2) {
                val key = parts[0].trim()
                val hmac = parts[1].trim()
                val rateLimit = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 60
                keyCache[key] = ApiKeyRecord(key, hmac, rateLimit)
                log.info("API key loaded: {}...", key.take(10))
            }
        }
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        // Only intercept /api/v1/public/** — skip all other paths
        val path = exchange.request.uri.path
        if (!path.startsWith("/api/v1/public/")) {
            return chain.filter(exchange)
        }

        val apiKey = exchange.request.headers.getFirst("X-API-Key")
        if (apiKey.isNullOrBlank()) {
            exchange.response.statusCode = org.springframework.http.HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }

        val record = keyCache[apiKey]
        if (record == null) {
            log.warn("Invalid API key attempt: {}...", apiKey.take(10))
            exchange.response.statusCode = org.springframework.http.HttpStatus.FORBIDDEN
            return exchange.response.setComplete()
        }

        val limiter = rateLimiters.computeIfAbsent(record.keyValue) { SlidingWindowCounter(record.rateLimit) }
        if (!limiter.allow()) {
            log.warn("Rate limit exceeded for API key: {}...", record.keyValue.take(10))
            exchange.response.statusCode = org.springframework.http.HttpStatus.TOO_MANY_REQUESTS
            return exchange.response.setComplete()
        }

        val timestamp = exchange.request.headers.getFirst("X-Timestamp")
        val signature = exchange.request.headers.getFirst("X-Signature")

        if (!timestamp.isNullOrBlank() && !signature.isNullOrBlank()) {
            // HMAC verification
            val now = Instant.now().epochSecond
            val ts = timestamp.toLongOrNull() ?: 0L
            if (Math.abs(now - ts) > 300) {
                log.warn("HMAC timestamp expired: {} vs now {}", ts, now)
                exchange.response.statusCode = org.springframework.http.HttpStatus.UNAUTHORIZED
                return exchange.response.setComplete()
            }

            val body = "" // GET requests have no body
            val payload = body + timestamp
            val expectedSig = hmacSha256(payload, record.hmacSecret)
            if (!constantTimeEquals(expectedSig, signature)) {
                log.warn("HMAC signature mismatch for key: {}...", record.keyValue.take(10))
                exchange.response.statusCode = org.springframework.http.HttpStatus.FORBIDDEN
                return exchange.response.setComplete()
            }
        }
        // else: plain key already validated by map lookup — fallback OK

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
