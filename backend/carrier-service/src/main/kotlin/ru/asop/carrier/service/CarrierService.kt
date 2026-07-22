package ru.asop.carrier.service

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.carrier.dto.request.CarrierCreateRequest
import ru.asop.api.carrier.dto.request.CarrierUpdateRequest
import ru.asop.api.carrier.dto.response.CarrierResponse
import ru.asop.carrier.model.CarrierEntity
import ru.asop.carrier.repository.CarrierRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class CarrierService(
    private val template: R2dbcEntityTemplate,
    private val carrierRepository: CarrierRepository
) {

    fun create(request: CarrierCreateRequest): Mono<CarrierResponse> {
        val now = Instant.now()
        val entity = CarrierEntity(
            carrierId = UuidUtils.newId(),
            carrierName = request.carrierName,
            inn = request.inn,
            regionId = request.regionId,
            createdAt = now,
            updatedAt = now
        )
        return template.insert(entity).map { it.toResponse() }
    }

    fun update(id: UUID, request: CarrierUpdateRequest): Mono<CarrierResponse> {
        return carrierRepository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    carrierName = request.carrierName ?: existing.carrierName,
                    inn = request.inn ?: existing.inn,
                    regionId = request.regionId ?: existing.regionId,
                    updatedAt = Instant.now()
                )
                carrierRepository.save(updated).map { it.toResponse() }
            }
    }

    fun findAll(): Flux<CarrierResponse> =
        carrierRepository.findAll().map { it.toResponse() }

    fun getById(id: UUID): Mono<CarrierResponse> {
        return carrierRepository.findById(id).map { it.toResponse() }
    }

    fun delete(id: UUID): Mono<Void> {
        return carrierRepository.deleteById(id)
    }
}

private fun CarrierEntity.toResponse() = CarrierResponse(
    id = carrierId,
    carrierName = carrierName,
    inn = inn,
    regionId = regionId,
    createdAt = createdAt,
    updatedAt = updatedAt
)
