package ru.asop.api.user.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.dto.request.UserCreateRequest
import ru.asop.api.user.dto.response.UserResponse

@RequestMapping("/api/v1/admin-users")
interface UserAdminApi {

    @GetMapping
    fun list(): Flux<UserResponse>

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): Mono<ResponseEntity<UserResponse>>

    @PostMapping
    fun create(@Valid @RequestBody request: UserCreateRequest): Mono<ResponseEntity<UserResponse>>

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: UserCreateRequest
    ): Mono<ResponseEntity<UserResponse>>

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): Mono<ResponseEntity<Void>>
}
