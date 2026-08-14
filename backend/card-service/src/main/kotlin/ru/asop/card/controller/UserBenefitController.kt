package ru.asop.card.controller

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.card.model.UserBenefitEntity
import ru.asop.card.repository.UserBenefitRepository
import java.time.Instant
import java.util.UUID

@RestController
class UserBenefitController(
    private val repository: UserBenefitRepository,
    private val template: R2dbcEntityTemplate
) {

    @GetMapping("/api/v1/user-benefits/delta")
    fun listDelta(
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<UserBenefitEntity> = repository.findDelta(userIdsIn, versionSince, includeDeleted, limit)

    /** Список назначений льгот. Фильтры: userId, benefitId. */
    @GetMapping("/api/v1/user-benefits")
    fun list(
        @RequestParam(required = false) userId: UUID?,
        @RequestParam(required = false) benefitId: UUID?
    ): Flux<UserBenefitEntity> =
        repository.findAll()
            .filter { it.deletedAt == null }
            .filter { userId == null || it.userId == userId }
            .filter { benefitId == null || it.benefitId == benefitId }

    data class UserBenefitCreateRequest(
        val userId: UUID,
        val benefitId: UUID,
        val validFrom: Instant? = null,
        val validUntil: Instant? = null
    )

    @PostMapping("/api/v1/user-benefits")
    fun create(@RequestBody req: UserBenefitCreateRequest): Mono<UserBenefitEntity> {
        val entity = UserBenefitEntity(
            assignmentId = ru.asop.common.util.UuidUtils.newId(),
            userId = req.userId,
            benefitId = req.benefitId,
            validFrom = req.validFrom ?: Instant.now(),
            validUntil = req.validUntil
        )
        // R2dbcEntityTemplate.insert — save() с non-null UUID делает UPDATE (AGENTS.md "Save bug")
        return template.insert(entity)
    }

    @DeleteMapping("/api/v1/user-benefits/{id}")
    fun delete(@PathVariable id: UUID): Mono<Void> =
        repository.findById(id)
            .flatMap { repository.deleteById(id) }  // trigger → soft-delete (DELETED_AT)
}
