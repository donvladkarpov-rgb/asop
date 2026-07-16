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
import ru.asop.api.route.dto.request.FareZoneCreateRequest
import ru.asop.api.route.dto.response.FareZoneResponse

@RequestMapping("/api/v1/fare-zones")
interface FareZoneApi {

    @GetMapping
    fun listFareZones(): Flux<FareZoneResponse>

    @GetMapping("/{id}")
    fun getFareZone(
        @PathVariable id: String
    ): Mono<ResponseEntity<FareZoneResponse>>

    @PostMapping
    fun createFareZone(
        @Valid @RequestBody request: FareZoneCreateRequest
    ): Mono<ResponseEntity<FareZoneResponse>>

    @PutMapping("/{id}")
    fun updateFareZone(
        @PathVariable id: String,
        @Valid @RequestBody request: FareZoneCreateRequest
    ): Mono<ResponseEntity<FareZoneResponse>>

    @DeleteMapping("/{id}")
    fun deleteFareZone(
        @PathVariable id: String
    ): Mono<ResponseEntity<Void>>
}
