package ru.asop.api.tid.controller

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
import ru.asop.api.tid.dto.request.TidCreateRequest
import ru.asop.api.tid.dto.request.TidUpdateRequest
import ru.asop.api.tid.dto.response.TidResponse
import java.util.UUID

@RequestMapping("/api/v1/tids")
interface TidApi {

    @GetMapping
    fun listTids(@RequestParam(required = false) carrierId: UUID?): Flux<TidResponse>

    @GetMapping("/{id}")
    fun getTid(@PathVariable id: UUID): Mono<ResponseEntity<TidResponse>>

    @PostMapping
    fun createTid(@Valid @RequestBody request: TidCreateRequest): Mono<ResponseEntity<TidResponse>>

    @PutMapping("/{id}")
    fun updateTid(@PathVariable id: UUID, @Valid @RequestBody request: TidUpdateRequest): Mono<ResponseEntity<TidResponse>>

    @DeleteMapping("/{id}")
    fun deleteTid(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}
