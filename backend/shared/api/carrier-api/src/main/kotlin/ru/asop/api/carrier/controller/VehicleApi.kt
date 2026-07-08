package ru.asop.api.carrier.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.carrier.dto.request.VehicleCreateRequest
import ru.asop.api.carrier.dto.response.VehicleResponse
import java.security.Principal
import java.util.UUID

@RequestMapping("/api/v1/vehicles")
interface VehicleApi {

    @PostMapping
    fun createVehicle(
        @Valid @RequestBody request: VehicleCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<VehicleResponse>>

    @GetMapping("/{id}")
    fun getVehicle(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<VehicleResponse>>
}
