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
import ru.asop.api.route.dto.request.VehicleCreateRequest
import ru.asop.api.route.dto.response.VehicleResponse

@RequestMapping("/api/v1/vehicles")
interface VehicleApi {

    @GetMapping
    fun list(
        @RequestParam(required = false) regionId: java.util.UUID? = null,
        @RequestParam(required = false) carrierId: java.util.UUID? = null
    ): Flux<VehicleResponse>

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): Mono<ResponseEntity<VehicleResponse>>

    @PostMapping
    fun create(@Valid @RequestBody request: VehicleCreateRequest): Mono<ResponseEntity<VehicleResponse>>

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: VehicleCreateRequest
    ): Mono<ResponseEntity<VehicleResponse>>

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): Mono<ResponseEntity<Void>>
}
