package ru.asop.gateway.controller

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import ru.asop.gateway.config.ServiceRegistry
import java.net.URI

@RestController
@RequestMapping("/api/v1/{resource}/**")
class ProxyController(
    private val serviceRegistry: ServiceRegistry,
    private val proxyWebClient: WebClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @RequestMapping
    fun proxy(
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<String>> {
        val request = exchange.request
        val path = request.uri.path

        val resource = extractResource(path) ?: return Mono.just(ResponseEntity.notFound().build())
        val baseUrl = serviceRegistry.getBaseUrl(resource)

        if (baseUrl == null) {
            log.debug("No service registered for resource '{}'", resource)
            return Mono.just(ResponseEntity.notFound().build())
        }

        val pathPrefix = "/api/v1/$resource"
        val pathSuffix = path.removePrefix(pathPrefix)
        val targetUri = URI.create("$baseUrl$pathPrefix$pathSuffix${queryString(exchange)}")

        log.debug("Proxying {} {} -> {}", request.method, path, targetUri)

        return extractIdentity(exchange).flatMap { identity ->
            proxyWebClient.method(request.method)
                .uri(targetUri)
                .headers { headers ->
                    val ct = request.headers.getFirst(HttpHeaders.CONTENT_TYPE)
                    if (ct != null) headers.set(HttpHeaders.CONTENT_TYPE, ct)
                    if (identity != null) {
                        headers.set(identity.first, identity.second)
                    }
                }
                .body(BodyInserters.fromDataBuffers(request.body))
                .exchangeToMono { clientResponse ->
                    clientResponse.bodyToMono(String::class.java)
                        .defaultIfEmpty("")
                        .map { body ->
                            ResponseEntity.status(clientResponse.statusCode())
                                .body(body)
                        }
                }
                .doOnError { e ->
                    log.error("Proxy failed for {} {}: {}", request.method, path, e.message)
                }
        }
    }

    private fun extractIdentity(exchange: ServerWebExchange): Mono<Pair<String, String>?> {
        return ReactiveSecurityContextHolder.getContext().flatMap { ctx ->
            val auth = ctx.authentication
            val identity: Pair<String, String>? = when {
                auth?.principal is Jwt -> {
                    val sub = (auth.principal as Jwt).subject
                    if (sub != null) Pair("X-Keycloak-Id", sub) else null
                }
                auth != null -> Pair("X-Terminal-Serial", auth.name)
                else -> null
            }
            Mono.justOrEmpty(identity)
        }.onErrorResume {
            log.warn("Failed to extract identity: {}", it.message)
            Mono.empty()
        }
    }

    private fun extractResource(path: String): String? {
        val parts = path.removePrefix("/").split("/")
        return if (parts.size >= 2 && parts[0] == "api" && parts[1] == "v1") {
            parts.getOrNull(2)
        } else null
    }

    private fun queryString(exchange: ServerWebExchange): String {
        val query = exchange.request.uri.rawQuery
        return if (query.isNullOrBlank()) "" else "?$query"
    }
}
