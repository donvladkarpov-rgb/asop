package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class PathTransportStopCreateRequest(
    @field:NotBlank(message = "Path ID is required")
    val pathId: String,

    @field:NotBlank(message = "Stop ID is required")
    val stopId: String,

    @field:NotNull(message = "Serial number is required")
    val serialNumber: Int,

    @field:NotNull(message = "Region ID is required")
    val regionId: String
)
