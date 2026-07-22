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
import ru.asop.api.user.dto.request.UserRegionCreateRequest
import ru.asop.api.user.dto.response.UserRegionResponse

@RequestMapping("/api/v1/user-regions")
interface UserRegionApi {

    @GetMapping
    fun list(
        @RequestParam("userId", required = false) userId: String? = null,
        @RequestParam("regionId", required = false) regionId: String? = null
    ): Flux<UserRegionResponse>

    @PostMapping
    fun create(@Valid @RequestBody request: UserRegionCreateRequest): Mono<ResponseEntity<UserRegionResponse>>

    @DeleteMapping
    fun delete(
        @RequestParam("userId") userId: String,
        @RequestParam("regionId") regionId: String
    ): Mono<ResponseEntity<Void>>
}
