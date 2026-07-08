package ru.asop.api.user.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.user.dto.request.ChangePasswordRequest
import java.security.Principal

@RequestMapping("/api/v1/users")
interface UserApi {

    @PostMapping("/password/change")
    fun changePassword(
        @Valid @RequestBody request: ChangePasswordRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<Void>>
}
