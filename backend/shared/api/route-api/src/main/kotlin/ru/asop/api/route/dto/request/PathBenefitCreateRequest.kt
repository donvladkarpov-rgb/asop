package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank

data class PathBenefitCreateRequest(
    @field:NotBlank(message = "Path ID is required")
    val pathId: String,

    @field:NotBlank(message = "Benefit ID is required")
    val benefitId: String
)
