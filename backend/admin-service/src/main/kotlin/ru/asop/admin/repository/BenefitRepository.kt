package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.admin.model.BenefitEntity
import java.util.UUID

@Repository
interface BenefitRepository : R2dbcRepository<BenefitEntity, UUID> {

    fun findByRegionId(regionId: UUID): Flux<BenefitEntity>
}
