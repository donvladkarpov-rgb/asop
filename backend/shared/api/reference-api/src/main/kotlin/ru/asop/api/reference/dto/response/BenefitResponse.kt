package ru.asop.api.reference.dto.response

import java.util.UUID

data class BenefitResponse(
    val id: UUID,
    val benefitCode: String,
    val benefitName: String,
    val regionId: UUID,
    val description: String?,
    val isActive: Boolean
)
