package ru.asop.api.reference.dto.response

import java.util.UUID

data class BenefitStepResponse(
    val id: UUID,
    val benefitId: UUID,
    val stepOrder: Int,
    val tripThresholdFrom: Int?,
    val tripThresholdTo: Int?,
    val discountShare: Double?,
    val periodType: String?
)
