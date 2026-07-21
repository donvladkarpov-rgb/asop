package ru.asop.carrier.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.carrier.controller.CardsDistributorApi
import ru.asop.api.carrier.dto.request.CardsDistributorCreateRequest
import ru.asop.api.carrier.dto.request.CardsDistributorUpdateRequest
import ru.asop.api.carrier.dto.response.CardsDistributorResponse
import ru.asop.carrier.service.CardsDistributorService
import java.util.UUID

@RestController
class CardsDistributorController(
    private val service: CardsDistributorService
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
}
