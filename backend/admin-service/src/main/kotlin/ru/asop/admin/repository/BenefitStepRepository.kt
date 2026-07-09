package ru.asop.admin.repository

import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import ru.asop.admin.model.BenefitStepEntity
import java.util.UUID

@Repository
interface BenefitStepRepository : R2dbcRepository<BenefitStepEntity, UUID> {

    fun findByBenefitId(benefitId: UUID): Flux<BenefitStepEntity>
}
