package ru.asop.gateway.controller

import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.gateway.dto.request.PortalInjectRequest
import ru.asop.gateway.service.PortalInjectService

/**
 * Промпт 013 Task 3: end-to-end Kafka inject tool — REST endpoint.
 *
 * POST /api/v1/admin/portal-inject
 *
 * Auth: JWT bearer token от Keycloak (доступ к эндпоинту).
 * Дополнительный рубеж: fixed header X-Portal-Token, сверяется с PORTAL_INJECT_TOKEN
 * из env. Это shared-secret между web-admin / CLI / docker-compose — позволяет
 * GitOps/CD tool, который не имеет Keycloak-токена, воспроизводимо inject'ить
 * события для smoke-tests / chaos / watermark-validation.
 *
 * Header X-Portal-Token допускается ONLY для отладочной инфраструктуры.
 * В PRODUCTION-окружении PORTAL_INJECT_TOKEN НЕ устанавливается → endpoint
 * возвращает 403 even with valid JWT (через SecurityConfig pathMatchers rule).
 *
 * Возвращает: 202 Accepted + JSON { eventId, topic, partition, offset }.
 *
 * Restriction: topic pattern валидируется через @Pattern (DTO):
 *   /\^asop\\.[a-z0-9]+\\\.(commands|events|issued|full\\.commands)\$/
 * Реальные consumer-services валидируют domain поля при обработке.
 */
@RestController
@RequestMapping("/api/v1/admin")
class PortalInjectController(
    private val portalInjectService: PortalInjectService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/portal-inject")
    fun inject(
        @RequestHeader("X-Portal-Token", required = false) portalToken: String?,
        @Valid @RequestBody request: PortalInjectRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): Mono<ResponseEntity<PortalInjectService.PortalInjectResult>> {
        if (configuredPortalToken.isBlank()) {
            log.warn(
                "PortalInject disabled: PORTAL_INJECT_TOKEN not configured. " +
                    "Set this env var to enable chaos/inject tools."
            )
            return Mono.just(
                ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(null as PortalInjectService.PortalInjectResult?)
            )
        }
        if (portalToken != configuredPortalToken) {
            log.warn(
                "PortalInject rejected: invalid X-Portal-Token from sub={}",
                jwt.subject
            )
            return Mono.just(
                ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(null as PortalInjectService.PortalInjectResult?)
            )
        }

        return portalInjectService.publish(request)
            .map { result -> ResponseEntity.status(HttpStatus.ACCEPTED).body(result) }
            .doOnSuccess { responseEntity: ResponseEntity<PortalInjectService.PortalInjectResult>? ->
                val body = responseEntity?.body
                if (body != null) {
                    log.info(
                        "PortalInject by admin sub={}: eventId={} topic={} partition={} offset={}",
                        jwt.subject, body.eventId, request.topic,
                        body.partition, body.offset
                    )
                }
            }
    }

    @Value("\${asop.portal-inject.token:}")
    private var configuredPortalToken: String = ""
}
