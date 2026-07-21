package ru.asop.carrier.service

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.carrier.dto.request.CardsDistributorCreateRequest
import ru.asop.api.carrier.dto.request.CardsDistributorUpdateRequest
import ru.asop.api.carrier.dto.response.CardsDistributorResponse
import ru.asop.api.carrier.dto.response.ContractResponse
import ru.asop.carrier.model.CardsDistributorEntity
import ru.asop.carrier.model.ContractEntity
import ru.asop.carrier.repository.CardsDistributorRepository
import ru.asop.carrier.repository.ContractRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class CardsDistributorService(
    private val template: R2dbcEntityTemplate,
    private val repository: CardsDistributorRepository,
    private val contractRepository: ContractRepository
) {

    fun create(request: CardsDistributorCreateRequest): Mono<CardsDistributorResponse> {
        val now = Instant.now()
        val entity = CardsDistributorEntity(
            cardsDistributorId = UuidUtils.newId(),
            distributorName = request.distributorName,
            inn = request.inn,
            kpp = request.kpp,
            legalAddress = request.legalAddress,
            contactPhone = request.contactPhone,
            contactEmail = request.contactEmail,
            isActive = request.isActive,
            createdAt = now,
            updatedAt = now
        )
        return template.insert(entity).map { it.toResponse(emptyList()) }
    }

    fun update(id: UUID, request: CardsDistributorUpdateRequest): Mono<CardsDistributorResponse> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    distributorName = request.distributorName ?: existing.distributorName,
                    inn = request.inn ?: existing.inn,
                    kpp = request.kpp ?: existing.kpp,
                    legalAddress = request.legalAddress ?: existing.legalAddress,
                    contactPhone = request.contactPhone ?: existing.contactPhone,
                    contactEmail = request.contactEmail ?: existing.contactEmail,
                    isActive = request.isActive ?: existing.isActive,
                    updatedAt = Instant.now()
                )
                repository.save(updated).flatMap { it.toResponseWithContracts() }
            }
            .switchIfEmpty(Mono.error(IllegalArgumentException("Cards distributor not found: $id")))
    }

    fun findAll(): Flux<CardsDistributorResponse> =
        repository.findAll().flatMap { it.toResponseWithContracts() }

    fun getById(id: UUID): Mono<CardsDistributorResponse> =
        repository.findById(id)
            .flatMap { it.toResponseWithContracts() }
            .switchIfEmpty(Mono.error(IllegalArgumentException("Cards distributor not found: $id")))

    fun delete(id: UUID): Mono<Void> =
        repository.deleteById(id)

    private fun CardsDistributorEntity.toResponseWithContracts(): Mono<CardsDistributorResponse> =
        contractRepository.findByCardsDistributorId(cardsDistributorId)
            .map { it.toResponse() }
            .collectList()
            .map { contracts -> toResponse(contracts) }
}

private fun CardsDistributorEntity.toResponse(contracts: List<ContractResponse>) = CardsDistributorResponse(
    id = cardsDistributorId,
    distributorName = distributorName,
    inn = inn,
    kpp = kpp,
    legalAddress = legalAddress,
    contactPhone = contactPhone,
    contactEmail = contactEmail,
    isActive = isActive,
    createdAt = createdAt,
    updatedAt = updatedAt,
    contracts = contracts
)

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
