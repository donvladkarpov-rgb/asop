package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class PathServiceCreateRequest(
    @field:NotBlank(message = "Path ID is required")
    val pathId: String,

    @field:NotBlank(message = "Service ID is required")
    val serviceId: String,

    val carrierId: String? = null,

    val vehicleId: String? = null,

    val tariffTypeId: String? = null,

    @field:NotNull(message = "Price is required")
    val price: Double,

    val isActive: Boolean? = null
)
