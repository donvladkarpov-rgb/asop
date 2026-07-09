package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.BenefitEntity
import ru.asop.admin.repository.BenefitRepository
import ru.asop.api.reference.controller.BenefitApi
import ru.asop.api.reference.dto.request.BenefitCreateRequest
import ru.asop.api.reference.dto.request.BenefitUpdateRequest
import ru.asop.api.reference.dto.response.BenefitResponse
import ru.asop.common.util.UuidUtils
import java.time.LocalDateTime
import java.util.UUID

@RestController
class BenefitController(
    private val repository: BenefitRepository,
    private val template: R2dbcEntityTemplate
) : BenefitApi {

    override fun listBenefits(): Mono<ResponseEntity<List<BenefitResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getBenefit(id: UUID): Mono<ResponseEntity<BenefitResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createBenefit(request: BenefitCreateRequest): Mono<ResponseEntity<BenefitResponse>> {
        val now = LocalDateTime.now()
        val entity = BenefitEntity(
            benefitId = UuidUtils.newId(),
            benefitCode = request.benefitCode,
            benefitName = request.benefitName,
            regionId = request.regionId,
            description = request.description,
            isActive = request.isActive ?: true,
            createdAt = now,
            updatedAt = now
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateBenefit(id: UUID, request: BenefitUpdateRequest): Mono<ResponseEntity<BenefitResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    benefitCode = request.benefitCode ?: existing.benefitCode,
                    benefitName = request.benefitName ?: existing.benefitName,
                    regionId = request.regionId ?: existing.regionId,
                    description = request.description ?: existing.description,
                    isActive = request.isActive ?: existing.isActive,
                    updatedAt = LocalDateTime.now()
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteBenefit(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun BenefitEntity.toResponse() = BenefitResponse(
        id = benefitId,
        benefitCode = benefitCode,
        benefitName = benefitName,
        regionId = regionId,
        description = description,
        isActive = isActive
    )
}
