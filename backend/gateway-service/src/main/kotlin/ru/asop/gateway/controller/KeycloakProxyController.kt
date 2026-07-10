package ru.asop.gateway.controller

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI

@RestController
@RequestMapping("/realms/**")
class KeycloakProxyController(
    private val keycloakWebClient: WebClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val keycloakBaseUrl =
        System.getenv("KEYCLOAK_INTERNAL_URL") ?: if (System.getenv("ASOP_ENV") == "docker")
            "https://keycloak:8443" else "http://localhost:8180"

    @RequestMapping
    fun proxy(exchange: ServerWebExchange): Mono<ResponseEntity<String>> {
        val request = exchange.request
        val path = request.uri.path
        val targetUri = URI.create("$keycloakBaseUrl$path${queryString(exchange)}")

        log.debug("Proxying {} {} -> {}", request.method, path, targetUri)

        return keycloakWebClient.method(request.method)
            .uri(targetUri)
            .headers { headers ->
                request.headers.forEach { (name, values) ->
                    if (name != "Host" && name != "Content-Length") {
                        values.forEach { headers.add(name, it) }
                    }
                }
                headers.set("X-Forwarded-Host", request.uri.host ?: "localhost")
                headers.set("X-Forwarded-Proto", request.uri.scheme ?: "http")
            }
            .body(BodyInserters.fromDataBuffers(request.body))
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .defaultIfEmpty("")
                    .map { body ->
                        ResponseEntity.status(clientResponse.statusCode())
                            .headers { responseHeaders ->
                                clientResponse.headers().asHttpHeaders().forEach { (name, values) ->
                                    if (name != "Transfer-Encoding") {
                                        values.forEach { responseHeaders.add(name, it) }
                                    }
                                }
                            }
                            .body(body)
                    }
            }
            .doOnError { e ->
                log.error("Keycloak proxy failed for {} {}: {}", request.method, path, e.message)
            }
    }

    private fun queryString(exchange: ServerWebExchange): String {
        val query = exchange.request.uri.rawQuery
        return if (query.isNullOrBlank()) "" else "?$query"
    }
}
