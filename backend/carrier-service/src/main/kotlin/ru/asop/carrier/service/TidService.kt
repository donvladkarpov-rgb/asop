package ru.asop.carrier.service

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.tid.dto.request.TidCreateRequest
import ru.asop.api.tid.dto.request.TidUpdateRequest
import ru.asop.api.tid.dto.response.TidResponse
import ru.asop.carrier.model.TidEntity
import ru.asop.carrier.repository.TidRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class TidService(
    private val template: R2dbcEntityTemplate,
    private val tidRepository: TidRepository
) {

    fun list(carrierId: UUID?, regionId: UUID?): Flux<TidResponse> {
        val entities = when {
            carrierId != null -> tidRepository.findByCarrierId(carrierId)
            regionId != null -> tidRepository.findByRegionId(regionId)
            else -> tidRepository.findAll()
        }
        return entities.map { it.toResponse() }
    }

    fun getById(id: UUID): Mono<TidResponse> =
        tidRepository.findById(id).map { it.toResponse() }

    fun create(request: TidCreateRequest): Mono<TidResponse> {
        val now = Instant.now()
        val entity = TidEntity(
            tidId = UuidUtils.newId(),
            carrierId = request.carrierId,
            tidValue = request.tidValue,
            status = "UNUSED",
            createdAt = now,
            updatedAt = now
        )
        return template.insert(entity).map { it.toResponse() }
    }

    fun update(id: UUID, request: TidUpdateRequest): Mono<TidResponse> {
        return tidRepository.findById(id)
            .flatMap { existing ->
                val now = Instant.now()
                val newStatus = request.status ?: existing.status
                val assignedAt = when {
                    request.status == "ASSIGNED" && existing.status != "ASSIGNED" -> now
                    else -> existing.assignedAt
                }
                val unassignedAt = when {
                    request.status != null && request.status != "ASSIGNED" && existing.status == "ASSIGNED" -> now
                    request.terminalId == null && existing.terminalId != null -> now
                    else -> existing.unassignedAt
                }
                val updated = existing.copy(
                    carrierId = request.carrierId ?: existing.carrierId,
                    tidValue = request.tidValue ?: existing.tidValue,
                    status = newStatus,
                    terminalId = request.terminalId,
                    assignedAt = assignedAt,
                    unassignedAt = unassignedAt,
                    updatedAt = now
                )
                tidRepository.save(updated).map { it.toResponse() }
            }
    }

    fun delete(id: UUID): Mono<Void> =
        tidRepository.deleteById(id)
}

private fun TidEntity.toResponse() = TidResponse(
    id = tidId,
    carrierId = carrierId,
    terminalId = terminalId,
    tidValue = tidValue,
    status = status,
    assignedAt = assignedAt,
    unassignedAt = unassignedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)
