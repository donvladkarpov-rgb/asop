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
import ru.asop.api.user.dto.request.UserDistributorCreateRequest
import ru.asop.api.user.dto.response.UserDistributorResponse

@RequestMapping("/api/v1/user-distributors")
interface UserDistributorApi {

    @GetMapping
    fun list(
        @RequestParam("userId", required = false) userId: String? = null,
        @RequestParam("cardsDistributorId", required = false) cardsDistributorId: String? = null
    ): Flux<UserDistributorResponse>

    @PostMapping
    fun create(@Valid @RequestBody request: UserDistributorCreateRequest): Mono<ResponseEntity<UserDistributorResponse>>

    @DeleteMapping
    fun delete(
        @RequestParam("userId") userId: String,
        @RequestParam("cardsDistributorId") cardsDistributorId: String
    ): Mono<ResponseEntity<Void>>
}
