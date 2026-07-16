package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class TransportStopCreateRequest(
    @field:NotBlank(message = "Stop code is required")
    val stopCode: String,

    @field:NotBlank(message = "Stop name is required")
    val stopName: String,

    @field:NotNull(message = "Region ID is required")
    val regionId: String,

    val fareZoneId: String? = null,

    val zonePolygon: String? = null,

    val stopAddress: String? = null,

    val description: String? = null,

    val isActive: Boolean? = null
)
