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
import java.time.Instant
import java.util.UUID
import reactor.core.publisher.Flux
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import ru.asop.admin.config.DeltaSupport

@RestController
class BenefitController(
    private val repository: BenefitRepository,
    private val template: R2dbcEntityTemplate
) : BenefitApi {

    override fun listBenefits(regionId: UUID?): Mono<ResponseEntity<List<BenefitResponse>>> {
        val benefits = if (regionId != null) {
            repository.findByRegionId(regionId)
        } else {
            repository.findAll()
        }
        return benefits
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
        val now = Instant.now()
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
                    updatedAt = Instant.now()
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


    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) regionId: java.util.UUID?,
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<BenefitEntity> {
        val extra = regionId?.let { Criteria.where("region_id").`is`(it) }
        return template.select(BenefitEntity::class.java)
            .matching(DeltaSupport.query(updatedAtSince, includeDeleted, limit, extra))
            .all()
    }
}
