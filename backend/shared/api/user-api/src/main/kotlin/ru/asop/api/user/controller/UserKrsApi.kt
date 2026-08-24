package ru.asop.api.user.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.dto.request.UserKrsCreateRequest
import ru.asop.api.user.dto.response.UserKrsResponse

@RequestMapping("/api/v1/user-krs")
interface UserKrsApi {

    @GetMapping
    fun list(
        @RequestParam("userId", required = false) userId: String? = null,
        @RequestParam("auditServiceId", required = false) auditServiceId: String? = null
    ): Flux<UserKrsResponse>

    @PostMapping
    fun create(@Valid @RequestBody request: UserKrsCreateRequest): Mono<ResponseEntity<UserKrsResponse>>

    @DeleteMapping
    fun delete(
        @RequestParam("userId") userId: String,
        @RequestParam("auditServiceId") auditServiceId: String
    ): Mono<ResponseEntity<Void>>
}
