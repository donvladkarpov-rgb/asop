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
import ru.asop.api.route.dto.request.PathServiceCreateRequest
import ru.asop.api.route.dto.response.PathServiceResponse

@RequestMapping("/api/v1/path-services")
interface PathServiceApi {

    @GetMapping
    fun list(): Flux<PathServiceResponse>

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String
    ): Mono<ResponseEntity<PathServiceResponse>>

    @PostMapping
    fun create(
        @Valid @RequestBody request: PathServiceCreateRequest
    ): Mono<ResponseEntity<PathServiceResponse>>

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: PathServiceCreateRequest
    ): Mono<ResponseEntity<PathServiceResponse>>

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: String
    ): Mono<ResponseEntity<Void>>
}
