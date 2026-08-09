package ru.asop.gateway.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import ru.asop.api.gateway.dto.request.RootLoginRequest
import ru.asop.api.gateway.dto.response.RootLoginResponse
import ru.asop.gateway.config.ServiceRegistry
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/**
 * POST /api/v1/sync/auth/root — логин рут-админа через Keycloak password grant
 * (client `asop-admin`, direct access grants). mTLS (chain Order 1).
 * Возвращает {userId} внутреннего ASOP-пользователя при роли SUPER_ADMIN.
 */
@RestController
class RootAuthController(
    @Qualifier("keycloakWebClient") private val keycloakWebClient: WebClient,
    @Qualifier("proxyWebClient") private val proxyWebClient: WebClient,
    private val serviceRegistry: ServiceRegistry,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val keycloakTokenUrl =
        (System.getenv("KEYCLOAK_INTERNAL_URL")?.let { "$it/realms/asop/protocol/openid-connect/token" })
            ?: if (System.getenv("ASOP_ENV") == "docker") {
                "https://keycloak:8443/realms/asop/protocol/openid-connect/token"
            } else {
                "http://localhost:8180/realms/asop/protocol/openid-connect/token"
            }

    @PostMapping("/api/v1/sync/auth/root")
    fun rootLogin(
        @RequestBody request: RootLoginRequest
    ): Mono<ResponseEntity<RootLoginResponse>> {
        return requestToken(request)
            .flatMap { tokenJson ->
                val accessToken = tokenJson.path("access_token").asText()
                if (accessToken.isBlank()) {
                    log.warn("Root login failed: no access_token in response")
                    return@flatMap Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build())
                }
                val payload = runCatching { decodeJwtPayload(accessToken) }.getOrNull()
                if (payload == null) {
                    log.warn("Root login failed: cannot decode access_token")
                    return@flatMap Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build())
                }
                val sub = payload.path("sub").asText()
                val roles = payload.path("realm_access").path("roles")
                    .takeIf { it.isArray }?.map { it.asText() }?.toSet() ?: emptySet()
                if ("SUPER_ADMIN" !in roles) {
                    log.warn("Root login for '{}' denied: no SUPER_ADMIN role", request.username)
                    Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build())
                } else {
                    resolveUserId(sub).map { userId ->
                        if (userId == null) {
                            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
                        } else {
                            ResponseEntity.ok(RootLoginResponse(userId = userId))
                        }
                    }
                }
            }
            .onErrorResume { err ->
                log.warn("Root login failed: {}", err.message)
                Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build())
            }
    }

    private fun requestToken(request: RootLoginRequest): Mono<JsonNode> {
        val body = "grant_type=password&client_id=asop-admin&username=${encode(request.username)}&password=${encode(request.password)}&scope=openid"
        return keycloakWebClient.post()
            .uri(keycloakTokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode::class.java)
    }

    private fun resolveUserId(sub: String): Mono<UUID?> {
        val baseUrl = serviceRegistry.getBaseUrl("admin-users") ?: return Mono.empty()
        return proxyWebClient.get()
            .uri("$baseUrl/api/v1/admin-users/by-keycloak/$sub")
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .map { node ->
                node.path("id").takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()?.let { UUID.fromString(it) }
            }
            .onErrorResume { err ->
                log.warn("Resolve userId by keycloak sub failed: {}", err.message)
                Mono.fromCallable { null as UUID? }
            }
    }

    private fun decodeJwtPayload(token: String): JsonNode? {
        val parts = token.split(".")
        if (parts.size < 2) return null
        val payload = Base64.getUrlDecoder().decode(parts[1])
        return objectMapper.readTree(payload)
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
}