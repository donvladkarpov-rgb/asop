package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.BenefitStepEntity
import ru.asop.admin.repository.BenefitStepDeltaQuery
import ru.asop.admin.repository.BenefitStepRepository
import ru.asop.api.reference.controller.BenefitStepApi
import ru.asop.api.reference.dto.request.BenefitStepCreateRequest
import ru.asop.api.reference.dto.request.BenefitStepUpdateRequest
import ru.asop.api.reference.dto.response.BenefitStepResponse
import ru.asop.common.util.UuidUtils
import java.math.BigDecimal
import java.util.UUID
import reactor.core.publisher.Flux
import org.springframework.data.domain.Sort
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam


@RestController
class BenefitStepController(
    private val repository: BenefitStepRepository,
    private val deltaQuery: BenefitStepDeltaQuery,
    private val template: R2dbcEntityTemplate
) : BenefitStepApi {

    override fun listBenefitSteps(): Mono<ResponseEntity<List<BenefitStepResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getBenefitStep(id: UUID): Mono<ResponseEntity<BenefitStepResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createBenefitStep(request: BenefitStepCreateRequest): Mono<ResponseEntity<BenefitStepResponse>> {
        val entity = BenefitStepEntity(
            stepId = UuidUtils.newId(),
            benefitId = request.benefitId,
            stepOrder = request.stepOrder,
            tripThresholdFrom = request.tripThresholdFrom ?: 0,
            tripThresholdTo = request.tripThresholdTo,
            discountShare = request.discountShare?.toBigDecimal() ?: BigDecimal.ZERO,
            periodType = request.periodType ?: "MONTHLY"
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateBenefitStep(id: UUID, request: BenefitStepUpdateRequest): Mono<ResponseEntity<BenefitStepResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    benefitId = request.benefitId ?: existing.benefitId,
                    stepOrder = request.stepOrder ?: existing.stepOrder,
                    tripThresholdFrom = request.tripThresholdFrom ?: existing.tripThresholdFrom,
                    tripThresholdTo = request.tripThresholdTo ?: existing.tripThresholdTo,
                    discountShare = request.discountShare?.toBigDecimal() ?: existing.discountShare,
                    periodType = request.periodType ?: existing.periodType
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteBenefitStep(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    override fun listByBenefitId(benefitId: UUID): Mono<ResponseEntity<List<BenefitStepResponse>>> {
        return repository.findByBenefitId(benefitId)
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    private fun BenefitStepEntity.toResponse() = BenefitStepResponse(
        id = stepId,
        benefitId = benefitId,
        stepOrder = stepOrder,
        tripThresholdFrom = tripThresholdFrom,
        tripThresholdTo = tripThresholdTo,
        discountShare = discountShare.toDouble(),
        periodType = periodType
    )


    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<BenefitStepEntity> = deltaQuery.findByRegion(versionSince, includeDeleted, limit, regionId)
}
