package ru.asop.user.controller

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserApi
import ru.asop.api.user.dto.request.ChangePasswordRequest
import ru.asop.user.service.KeycloakAdminService

@RestController
class UserController(
    private val keycloakAdminService: KeycloakAdminService
) : UserApi {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun changePassword(
        request: ChangePasswordRequest,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<Void>> {
        val keycloakId = exchange.request.headers.getFirst("X-Keycloak-Id")
        if (keycloakId == null) {
            log.warn("Missing X-Keycloak-Id header")
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build())
        }
        return Mono.fromCallable {
            keycloakAdminService.updatePassword(keycloakId, request.newPassword)
            ResponseEntity.status(HttpStatus.NO_CONTENT).build<Void>()
        }.doOnError { e ->
            log.error("Password change failed: {}", e.message)
        }
    }
}
