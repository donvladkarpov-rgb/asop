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
import ru.asop.api.user.dto.request.UserRoleCreateRequest
import ru.asop.api.user.dto.response.UserRoleResponse

@RequestMapping("/api/v1/user-roles")
interface UserRoleApi {

    @GetMapping
    fun list(
        @RequestParam("userId", required = false) userId: String? = null,
        @RequestParam("roleId", required = false) roleId: String? = null
    ): Flux<UserRoleResponse>

    @PostMapping
    fun create(@Valid @RequestBody request: UserRoleCreateRequest): Mono<ResponseEntity<UserRoleResponse>>

    @DeleteMapping
    fun delete(
        @RequestParam("userId") userId: String,
        @RequestParam("roleId") roleId: String
    ): Mono<ResponseEntity<Void>>
}
