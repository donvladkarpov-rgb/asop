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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.route.dto.request.PathTransportStopCreateRequest
import ru.asop.api.route.dto.response.PathTransportStopResponse

@RequestMapping("/api/v1/path-transport-stops")
interface PathTransportStopApi {

    @GetMapping
    fun list(): Flux<PathTransportStopResponse>

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String
    ): Mono<ResponseEntity<PathTransportStopResponse>>

    @PostMapping
    fun create(
        @Valid @RequestBody request: PathTransportStopCreateRequest
    ): Mono<ResponseEntity<PathTransportStopResponse>>

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: PathTransportStopCreateRequest
    ): Mono<ResponseEntity<PathTransportStopResponse>>

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: String
    ): Mono<ResponseEntity<Void>>
}
