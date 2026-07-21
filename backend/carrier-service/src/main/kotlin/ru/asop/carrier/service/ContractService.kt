package ru.asop.carrier.service

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.carrier.dto.request.ContractCreateRequest
import ru.asop.api.carrier.dto.request.ContractUpdateRequest
import ru.asop.api.carrier.dto.response.ContractResponse
import ru.asop.carrier.model.ContractEntity
import ru.asop.carrier.repository.ContractRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class ContractService(
    private val template: R2dbcEntityTemplate,
    private val repository: ContractRepository
) {

    fun create(request: ContractCreateRequest): Mono<ContractResponse> {
        if (request.carrierId != null && request.cardsDistributorId != null) {
            return Mono.error(IllegalArgumentException("Only one of carrierId or cardsDistributorId can be set, not both"))
        }
        val now = Instant.now()
        val entity = ContractEntity(
            contractId = UuidUtils.newId(),
            contractorType = request.contractorType,
            carrierId = request.carrierId,
            cardsDistributorId = request.cardsDistributorId,
            contractNumber = request.contractNumber,
            startDate = request.startDate,
            endDate = request.endDate,
            status = request.status,
            commissionPercent = request.commissionPercent,
            attributes = request.attributes,
            createdAt = now,
            updatedAt = now
        )
        return template.insert(entity).map { it.toResponse() }
    }

    fun update(id: UUID, request: ContractUpdateRequest): Mono<ContractResponse> {
        return repository.findById(id)
            .flatMap { existing ->
                val newCarrierId = if (request.clearCarrierId) null else (request.carrierId ?: existing.carrierId)
                val newDistributorId = if (request.clearCardsDistributorId) null else (request.cardsDistributorId ?: existing.cardsDistributorId)
                if (newCarrierId != null && newDistributorId != null) {
                    return@flatMap Mono.error<ContractResponse>(IllegalArgumentException("Only one of carrierId or cardsDistributorId can be set, not both"))
                }
                val updated = existing.copy(
                    contractorType = request.contractorType ?: existing.contractorType,
                    carrierId = newCarrierId,
                    cardsDistributorId = newDistributorId,
                    contractNumber = request.contractNumber ?: existing.contractNumber,
                    startDate = request.startDate ?: existing.startDate,
                    endDate = request.endDate ?: existing.endDate,
                    status = request.status ?: existing.status,
                    commissionPercent = request.commissionPercent ?: existing.commissionPercent,
                    attributes = request.attributes ?: existing.attributes,
                    updatedAt = Instant.now()
                )
                repository.save(updated).map { it.toResponse() }
            }
            .switchIfEmpty(Mono.error(IllegalArgumentException("Contract not found: $id")))
    }

    fun findAll(): Flux<ContractResponse> =
        repository.findAll().map { it.toResponse() }

    fun getById(id: UUID): Mono<ContractResponse> =
        repository.findById(id)
            .map { it.toResponse() }
            .switchIfEmpty(Mono.error(IllegalArgumentException("Contract not found: $id")))

    fun delete(id: UUID): Mono<Void> =
        repository.deleteById(id)
}

private fun ContractEntity.toResponse() = ContractResponse(
    id = contractId,
    contractorType = contractorType,
    carrierId = carrierId,
    cardsDistributorId = cardsDistributorId,
    contractNumber = contractNumber,
    startDate = startDate,
    endDate = endDate,
    status = status,
    commissionPercent = commissionPercent,
    attributes = attributes,
    createdAt = createdAt,
    updatedAt = updatedAt
)
