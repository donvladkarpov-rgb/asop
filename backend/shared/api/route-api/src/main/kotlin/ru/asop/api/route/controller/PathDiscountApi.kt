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
import ru.asop.api.route.dto.request.PathDiscountCreateRequest
import ru.asop.api.route.dto.response.PathDiscountResponse

@RequestMapping("/api/v1/path-discounts")
interface PathDiscountApi {

    @GetMapping
    fun list(
        @RequestParam(required = false) regionId: java.util.UUID? = null,
        @RequestParam(required = false) carrierId: java.util.UUID? = null
    ): Flux<PathDiscountResponse>

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String
    ): Mono<ResponseEntity<PathDiscountResponse>>

    @PostMapping
    fun create(
        @Valid @RequestBody request: PathDiscountCreateRequest
    ): Mono<ResponseEntity<PathDiscountResponse>>

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: PathDiscountCreateRequest
    ): Mono<ResponseEntity<PathDiscountResponse>>

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: String
    ): Mono<ResponseEntity<Void>>
}
