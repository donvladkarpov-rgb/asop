package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.Size
import java.util.UUID

data class BenefitUpdateRequest(
    @field:Size(max = 50)
    val benefitCode: String? = null,

    @field:Size(max = 100)
    val benefitName: String? = null,

    val regionId: UUID? = null,

    @field:Size(max = 65535)
    val description: String? = null,

    val isActive: Boolean? = null
)
