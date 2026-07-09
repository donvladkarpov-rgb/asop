package ru.asop.carrier.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.carrier.controller.CarrierApi
import ru.asop.api.carrier.dto.request.CarrierCreateRequest
import ru.asop.api.carrier.dto.request.CarrierUpdateRequest
import ru.asop.api.carrier.dto.response.CarrierResponse
import ru.asop.carrier.service.CarrierService
import java.security.Principal
import java.util.UUID

@RestController
class CarrierController(
    private val carrierService: CarrierService
) : CarrierApi {

    override fun createCarrier(
        request: CarrierCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CarrierResponse>> {
        return carrierService.create(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun updateCarrier(
        id: UUID,
        request: CarrierUpdateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CarrierResponse>> {
        return carrierService.update(id, request)
            .map { ResponseEntity.ok(it) }
    }

    override fun getCarrier(id: UUID): Mono<ResponseEntity<CarrierResponse>> {
        return carrierService.getById(id)
            .map { ResponseEntity.ok(it) }
    }
}
