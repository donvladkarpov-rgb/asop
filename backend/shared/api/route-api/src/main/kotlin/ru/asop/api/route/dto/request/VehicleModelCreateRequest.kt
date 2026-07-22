package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank

data class VehicleModelCreateRequest(
    @field:NotBlank
    val modelName: String
)
