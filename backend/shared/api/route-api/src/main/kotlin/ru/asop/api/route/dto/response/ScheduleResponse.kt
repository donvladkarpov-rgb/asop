package ru.asop.api.route.dto.response

data class ScheduleResponse(
    val id: String,
    val pathId: String,
    val stopId: String,
    val dayMask: Int,
    val arrivalTime: String,
    val dwellTimeSec: Int? = null,
    val regionId: String,
    val isActive: Boolean? = null
)
