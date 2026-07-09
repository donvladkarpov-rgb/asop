package ru.asop.api.user.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import ru.asop.api.user.dto.request.ChangePasswordRequest

@RequestMapping("/api/v1/users")
interface UserApi {

    @PostMapping("/password/change")
    fun changePassword(
        @Valid @RequestBody request: ChangePasswordRequest,
        exchange: ServerWebExchange
    ): Mono<ResponseEntity<Void>>
}
