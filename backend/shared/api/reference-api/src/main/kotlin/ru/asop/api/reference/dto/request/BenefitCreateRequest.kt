package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class BenefitCreateRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val benefitCode: String,

    @field:NotBlank
    @field:Size(max = 100)
    val benefitName: String,

    @field:NotNull
    val regionId: UUID,

    @field:Size(max = 65535)
    val description: String? = null,

    val isActive: Boolean? = null
)
