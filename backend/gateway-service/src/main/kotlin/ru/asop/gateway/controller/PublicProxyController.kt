package ru.asop.gateway.controller

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI

@RestController
@RequestMapping("/api/v1/public/**")
class PublicProxyController(
    private val proxyWebClient: WebClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val isDocker = System.getenv("ASOP_ENV") == "docker"

    private fun svc(host: String, port: Int): String {
        val h = if (isDocker) host else "localhost"
        return "https://$h:$port"
    }

    private fun resolveService(path: String): String? {
        val sub = path.removePrefix("/api/v1/public/")
        return when {
            sub.startsWith("tracking") -> svc("session-service", 8085)
            sub.startsWith("stops") -> svc("route-service", 8092)
            else -> null
        }
    }

    @RequestMapping
    fun proxy(exchange: ServerWebExchange): Mono<ResponseEntity<String>> {
        val request = exchange.request
        val path = request.uri.path
        val baseUrl = resolveService(path)

        if (baseUrl == null) {
            log.debug("No service for public path: {}", path)
            return Mono.just(ResponseEntity.notFound().build())
        }

        val pathPrefix = "/api/v1/"
        val pathSuffix = path.removePrefix(pathPrefix).removePrefix("public/")
        val targetUri = URI.create("$baseUrl$pathPrefix$pathSuffix${queryString(exchange)}")

        log.debug("Public proxy {} {} -> {}", request.method, path, targetUri)

        return proxyWebClient.method(request.method)
            .uri(targetUri)
            .headers { headers ->
                val ct = request.headers.getFirst(HttpHeaders.CONTENT_TYPE)
                if (ct != null) headers.set(HttpHeaders.CONTENT_TYPE, ct)
            }
            .body(BodyInserters.fromDataBuffers(request.body))
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(String::class.java)
                    .defaultIfEmpty("")
                    .map { body ->
                        ResponseEntity.status(clientResponse.statusCode()).body(body)
                    }
            }
            .doOnError { e ->
                log.error("Public proxy failed for {} {}: {}", request.method, path, e.message)
            }
    }

    private fun queryString(exchange: ServerWebExchange): String {
        val query = exchange.request.uri.rawQuery
        return if (query.isNullOrBlank()) "" else "?$query"
    }
}
