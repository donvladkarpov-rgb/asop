package ru.asop.gateway.controller

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import ru.asop.gateway.config.ServiceRegistry
import org.springframework.http.HttpMethod

/**
 * Синхронный серверный контур для android-distributor (mTLS, chain Order 1).
 * Дистрибьютор НЕ участвует в orchestrator delta-flow (там carrier/region-scope);
 * ему нужны глобальные справочники — ключи ASOP_KEYS и тарифы ASOP_TARIFF_RATES.
 * Pull напрямую с мастеров JSON-/delta (тот же keyset, что использует оркестратор).
 */
@RestController
class DistributorSyncController(
    private val serviceRegistry: ServiceRegistry,
    private val proxyWebClient: WebClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Идемпотентный онбординг: upsert ASOP_DISTRIBUTOR_TERMINALS по TERMINAL_SERIAL. */
    @PostMapping("/api/v1/sync/distributor/register")
    fun register(@RequestBody body: String): Mono<ResponseEntity<String>> {
        val base = serviceRegistry.getBaseUrl("distributor-terminals")
            ?: return Mono.just(ResponseEntity.notFound().build())
        return forward(
            HttpMethod.POST,
            "$base/api/v1/distributor-terminals/register",
            body
        )
    }

    /** Глобальный пул ключей карт (admin-service, ciphertext KEY_MATERIAL). */
    @GetMapping("/api/v1/sync/distributor/keys/delta")
    fun keysDelta(exchange: ServerWebExchange): Mono<ResponseEntity<String>> {
        val base = serviceRegistry.getBaseUrl("asop-keys")
            ?: return Mono.just(ResponseEntity.notFound().build())
        return forward(
            HttpMethod.GET,
            "$base/api/v1/asop-keys/delta${queryString(exchange)}",
            null
        )
    }

    /** Льготные тарифы (card-service). */
    @GetMapping("/api/v1/sync/distributor/tariffs/delta")
    fun tariffsDelta(exchange: ServerWebExchange): Mono<ResponseEntity<String>> {
        val base = serviceRegistry.getBaseUrl("tariff-rates")
            ?: return Mono.just(ResponseEntity.notFound().build())
        return forward(
            HttpMethod.GET,
            "$base/api/v1/tariff-rates/delta${queryString(exchange)}",
            null
        )
    }

    private fun forward(method: HttpMethod, uri: String, body: String?): Mono<ResponseEntity<String>> {
        log.info("distributor sync {} -> {}", method, uri)
        val spec = proxyWebClient.method(method).uri(uri)
        val prepared = if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
        } else {
            spec
        }
        return prepared.exchangeToMono { resp ->
            resp.bodyToMono(String::class.java)
                .defaultIfEmpty("")
                .map { text -> ResponseEntity.status(resp.statusCode()).body(text) }
        }.onErrorResume { err ->
            log.warn("distributor sync failed {} {}: {}", method, uri, err.message)
            Mono.just(ResponseEntity.status(org.springframework.http.HttpStatus.BAD_GATEWAY).build())
        }
    }

    private fun queryString(exchange: ServerWebExchange): String {
        val query = exchange.request.uri.rawQuery
        return if (query.isNullOrBlank()) "" else "?$query"
    }
}