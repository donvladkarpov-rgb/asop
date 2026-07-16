package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class VehicleCreateRequest(
    @field:NotNull
    val carrierId: String,
    @field:NotNull
    val vehicleTypeId: String,
    @field:NotNull
    val vehicleModelId: String,
    @field:NotBlank
    val vehicleNumber: String,
    @field:NotBlank
    val vehicleName: String
)
