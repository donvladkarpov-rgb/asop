package ru.asop.api.carrier.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.carrier.dto.request.CarrierCreateRequest
import ru.asop.api.carrier.dto.request.CarrierUpdateRequest
import ru.asop.api.carrier.dto.response.CarrierResponse
import java.security.Principal
import java.util.UUID

@RequestMapping("/api/v1/carriers")
interface CarrierApi {

    @PostMapping
    fun createCarrier(
        @Valid @RequestBody request: CarrierCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CarrierResponse>>

    @PutMapping("/{id}")
    fun updateCarrier(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CarrierUpdateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CarrierResponse>>

    @GetMapping("/{id}")
    fun getCarrier(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<CarrierResponse>>
}
