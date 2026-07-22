package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank

data class VehicleTypeCreateRequest(
    @field:NotBlank
    val typeName: String
)
