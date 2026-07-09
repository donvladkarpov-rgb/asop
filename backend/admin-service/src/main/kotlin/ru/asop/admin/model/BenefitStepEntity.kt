package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.util.UUID

@Table("ASOP_BENEFIT_STEPS")
data class BenefitStepEntity(
    @Id
    val stepId: UUID,
    val benefitId: UUID,
    val stepOrder: Int,
    val tripThresholdFrom: Int = 0,
    val tripThresholdTo: Int? = null,
    val discountShare: BigDecimal,
    val periodType: String
)
