package ru.asop.gateway.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import ru.asop.gateway.config.ServiceRegistry

/**
 * Sync-endpoints активации карт (mTLS, chain Order 1).
 * Gateway — только проксирование, без бизнес-логики.
 */
@RestController
class SyncCardController(
    private val serviceRegistry: ServiceRegistry,
    @Qualifier("proxyWebClient") private val webClient: WebClient,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/v1/sync/smart-cards/sign")
    fun signCardIdentity(
        @RequestBody body: JsonNode
    ): Mono<ResponseEntity<JsonNode>> {
        return proxy("smart-cards", "/api/v1/smart-cards/sign", body)
    }

    @PostMapping("/api/v1/sync/cards/activate")
    fun activateCard(
        @RequestBody body: JsonNode
    ): Mono<ResponseEntity<JsonNode>> {
        return proxy("cards", "/api/v1/cards/activate", body)
    }

    @GetMapping("/api/v1/sync/cards/by-uid/{uid}")
    fun getCardByUid(
        @PathVariable uid: String
    ): Mono<ResponseEntity<JsonNode>> {
        return getProxy("cards", "/api/v1/cards/by-uid/$uid")
    }

    private fun proxy(resource: String, path: String, body: JsonNode): Mono<ResponseEntity<JsonNode>> {
        val baseUrl = serviceRegistry.getBaseUrl(resource)
            ?: return Mono.just(ResponseEntity.notFound().build())
        val targetUri = "$baseUrl$path"
        log.debug("Proxying sync -> {} {}", targetUri, body)

        return webClient.post()
            .uri(targetUri)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .defaultIfEmpty("")
                    .map { resp ->
                        val status = clientResponse.statusCode()
                        val node = if (resp.isNotBlank()) {
                            runCatching { objectMapper.readTree(resp) }.getOrElse {
                                objectMapper.createObjectNode().put("error", resp)
                            }
                        } else {
                            objectMapper.createObjectNode()
                        }
                        ResponseEntity.status(status).body(node)
                    }
            }
            .onErrorResume { err ->
                log.error("Sync proxy failed {}: {}", targetUri, err.message)
                Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(objectMapper.createObjectNode().put("error", err.message ?: "proxy failed")))
            }
    }

    private fun getProxy(resource: String, path: String): Mono<ResponseEntity<JsonNode>> {
        val baseUrl = serviceRegistry.getBaseUrl(resource)
            ?: return Mono.just(ResponseEntity.notFound().build())
        val targetUri = "$baseUrl$path"
        log.debug("Proxying GET sync -> {}", targetUri)

        return webClient.get()
            .uri(targetUri)
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .defaultIfEmpty("")
                    .map { resp ->
                        val status = clientResponse.statusCode()
                        val node = if (resp.isNotBlank()) {
                            runCatching { objectMapper.readTree(resp) }.getOrElse {
                                objectMapper.createObjectNode().put("error", resp)
                            }
                        } else {
                            objectMapper.createObjectNode()
                        }
                        ResponseEntity.status(status).body(node)
                    }
            }
            .onErrorResume { err ->
                log.error("Sync GET proxy failed {}: {}", targetUri, err.message)
                Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(objectMapper.createObjectNode().put("error", err.message ?: "proxy failed")))
            }
    }
}