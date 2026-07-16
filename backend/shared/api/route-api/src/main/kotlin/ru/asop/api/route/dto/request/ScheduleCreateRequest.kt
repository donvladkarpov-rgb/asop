package ru.asop.api.route.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class ScheduleCreateRequest(
    @field:NotBlank(message = "Path ID is required")
    val pathId: String,

    @field:NotBlank(message = "Stop ID is required")
    val stopId: String,

    val dayMask: Int? = null,

    @field:NotBlank(message = "Arrival time is required")
    val arrivalTime: String,

    val dwellTimeSec: Int? = null,

    @field:NotNull(message = "Region ID is required")
    val regionId: String,

    val isActive: Boolean? = null
)
