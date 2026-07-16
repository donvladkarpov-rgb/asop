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
import ru.asop.api.route.dto.request.PathCreateRequest
import ru.asop.api.route.dto.response.PathResponse

@RequestMapping("/api/v1/paths")
interface PathApi {

    @GetMapping
    fun listPaths(): Flux<PathResponse>

    @GetMapping("/{id}")
    fun getPath(
        @PathVariable id: String
    ): Mono<ResponseEntity<PathResponse>>

    @PostMapping
    fun createPath(
        @Valid @RequestBody request: PathCreateRequest
    ): Mono<ResponseEntity<PathResponse>>

    @PutMapping("/{id}")
    fun updatePath(
        @PathVariable id: String,
        @Valid @RequestBody request: PathCreateRequest
    ): Mono<ResponseEntity<PathResponse>>

    @DeleteMapping("/{id}")
    fun deletePath(
        @PathVariable id: String
    ): Mono<ResponseEntity<Void>>
}
