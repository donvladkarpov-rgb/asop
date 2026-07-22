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
import ru.asop.api.user.dto.request.UserCarrierCreateRequest
import ru.asop.api.user.dto.response.UserCarrierResponse

@RequestMapping("/api/v1/user-carriers")
interface UserCarrierApi {

    @GetMapping
    fun list(
        @RequestParam("userId", required = false) userId: String? = null,
        @RequestParam("carrierId", required = false) carrierId: String? = null
    ): Flux<UserCarrierResponse>

    @PostMapping
    fun create(@Valid @RequestBody request: UserCarrierCreateRequest): Mono<ResponseEntity<UserCarrierResponse>>

    @DeleteMapping
    fun delete(
        @RequestParam("userId") userId: String,
        @RequestParam("carrierId") carrierId: String
    ): Mono<ResponseEntity<Void>>
}
