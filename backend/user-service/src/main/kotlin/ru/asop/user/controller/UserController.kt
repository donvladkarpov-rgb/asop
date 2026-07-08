package ru.asop.user.controller

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserApi
import ru.asop.api.user.dto.request.ChangePasswordRequest
import ru.asop.user.service.KeycloakAdminService
import java.security.Principal

@RestController
class UserController(
    private val keycloakAdminService: KeycloakAdminService
) : UserApi {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun changePassword(
        request: ChangePasswordRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<Void>> {
        return principal
            .switchIfEmpty(Mono.error(IllegalStateException("Principal required")))
            .flatMap { p ->
                Mono.fromCallable {
                    keycloakAdminService.updatePassword(p.name, request.newPassword)
                    ResponseEntity.status(HttpStatus.NO_CONTENT).build<Void>()
                }
            }
            .doOnError { e ->
                log.error("Password change failed: {}", e.message)
            }
    }
}
