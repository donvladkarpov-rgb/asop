package ru.asop.api.route.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.dto.request.RouteCreateRequest
import ru.asop.api.route.dto.response.RouteResponse

@RequestMapping("/api/v1/routes")
interface RouteApi {

    @GetMapping
    fun listRoutes(
        @RequestParam(required = false) regionId: java.util.UUID? = null,
        @RequestParam(required = false) carrierId: java.util.UUID? = null
    ): Flux<RouteResponse>

    @GetMapping("/{id}")
    fun getRoute(
        @PathVariable id: String
    ): Mono<ResponseEntity<RouteResponse>>

    @PostMapping
    fun createRoute(
        @Valid @RequestBody request: RouteCreateRequest
    ): Mono<ResponseEntity<RouteResponse>>

    @PutMapping("/{id}")
    fun updateRoute(
        @PathVariable id: String,
        @Valid @RequestBody request: RouteCreateRequest
    ): Mono<ResponseEntity<RouteResponse>>

    @DeleteMapping("/{id}")
    fun deleteRoute(
        @PathVariable id: String
    ): Mono<ResponseEntity<Void>>
}
