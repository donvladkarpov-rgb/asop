package ru.asop.api.carrier.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class VehicleCreateRequest(
    @field:NotNull(message = "Carrier ID is required")
    val carrierId: UUID,

    @field:NotNull(message = "Vehicle type ID is required")
    val vehicleTypeId: UUID,

    @field:NotNull(message = "Vehicle model ID is required")
    val vehicleModelId: UUID,

    @field:NotBlank(message = "Vehicle number is required")
    @field:Size(max = 16, message = "Vehicle number must be less than 16 characters")
    val vehicleNumber: String,

    @field:NotBlank(message = "Vehicle name is required")
    @field:Size(max = 255, message = "Vehicle name must be less than 255 characters")
    val vehicleName: String
)
