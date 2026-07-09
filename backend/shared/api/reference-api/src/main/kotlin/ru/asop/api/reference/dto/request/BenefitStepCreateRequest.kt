package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class BenefitStepCreateRequest(
    @field:NotNull
    val benefitId: UUID,

    @field:NotNull
    val stepOrder: Int,

    val tripThresholdFrom: Int? = null,

    val tripThresholdTo: Int? = null,

    @field:DecimalMin("0.00")
    @field:DecimalMax("1.00")
    val discountShare: Double? = null,

    @field:Size(max = 20)
    val periodType: String? = null
)
