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
import ru.asop.api.route.dto.request.TransportStopCreateRequest
import ru.asop.api.route.dto.response.TransportStopResponse

@RequestMapping("/api/v1/transport-stops")
interface TransportStopApi {

    @GetMapping
    fun list(
        @RequestParam(required = false) regionId: java.util.UUID? = null,
        @RequestParam(required = false) carrierId: java.util.UUID? = null
    ): Flux<TransportStopResponse>

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String
    ): Mono<ResponseEntity<TransportStopResponse>>

    @PostMapping
    fun create(
        @Valid @RequestBody request: TransportStopCreateRequest
    ): Mono<ResponseEntity<TransportStopResponse>>

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: TransportStopCreateRequest
    ): Mono<ResponseEntity<TransportStopResponse>>

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: String
    ): Mono<ResponseEntity<Void>>
}
