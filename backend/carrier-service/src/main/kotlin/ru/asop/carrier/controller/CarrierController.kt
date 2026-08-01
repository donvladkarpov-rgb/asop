package ru.asop.carrier.controller

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.carrier.controller.CarrierApi
import ru.asop.api.carrier.dto.request.CarrierCreateRequest
import ru.asop.api.carrier.dto.request.CarrierUpdateRequest
import ru.asop.api.carrier.dto.response.CarrierResponse
import ru.asop.carrier.config.DeltaSupport
import ru.asop.carrier.model.CarrierEntity
import ru.asop.carrier.service.CarrierService
import java.security.Principal
import java.time.Instant
import java.util.UUID

@RestController
class CarrierController(
    private val carrierService: CarrierService,
    private val template: R2dbcEntityTemplate
) : CarrierApi {

    override fun listCarriers(regionId: UUID?): Flux<CarrierResponse> = carrierService.findAll(regionId)

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

    override fun deleteCarrier(id: UUID): Mono<ResponseEntity<Void>> {
        return carrierService.delete(id)
            .thenReturn(ResponseEntity.noContent().build())
    }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<CarrierEntity> {
        val extra = mutableListOf<Criteria>()
        regionId?.let { extra += Criteria.where("region_id").`is`(it) }
        val query = if (extra.isEmpty()) {
            DeltaSupport.query(updatedAtSince, includeDeleted, limit)
        } else {
            DeltaSupport.query(updatedAtSince, includeDeleted, limit, Criteria.from(extra))
        }
        return template.select(CarrierEntity::class.java)
            .matching(query)
            .all()
    }
}
