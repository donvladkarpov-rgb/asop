package ru.asop.carrier.controller

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.carrier.controller.CardsDistributorApi
import ru.asop.api.carrier.dto.request.CardsDistributorCreateRequest
import ru.asop.api.carrier.dto.request.CardsDistributorUpdateRequest
import ru.asop.api.carrier.dto.response.CardsDistributorResponse
import ru.asop.carrier.config.DeltaSupport
import ru.asop.carrier.model.CardsDistributorEntity
import ru.asop.carrier.service.CardsDistributorService
import java.util.UUID

@RestController
class CardsDistributorController(
    private val service: CardsDistributorService,
    private val template: R2dbcEntityTemplate
) : CardsDistributorApi {

    override fun listCardsDistributors(): Flux<CardsDistributorResponse> = service.findAll()

    override fun getCardsDistributor(id: UUID): Mono<ResponseEntity<CardsDistributorResponse>> =
        service.getById(id).map { ResponseEntity.ok(it) }

    override fun createCardsDistributor(request: CardsDistributorCreateRequest): Mono<ResponseEntity<CardsDistributorResponse>> =
        service.create(request).map { ResponseEntity.ok(it) }

    override fun updateCardsDistributor(id: UUID, request: CardsDistributorUpdateRequest): Mono<ResponseEntity<CardsDistributorResponse>> =
        service.update(id, request).map { ResponseEntity.ok(it) }

    override fun deleteCardsDistributor(id: UUID): Mono<ResponseEntity<Void>> =
        service.delete(id).map { ResponseEntity.noContent().build() }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<CardsDistributorEntity> {
        return template.select(CardsDistributorEntity::class.java)
            .matching(DeltaSupport.query(versionSince, includeDeleted, limit))
            .all()
    }
}
