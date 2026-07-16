package ru.asop.api.route.dto.response

data class PathResponse(
    val id: String,
    val routeId: String,
    val pathName: String,
    val routeObject: String?,
    val benefitPolicy: String,
    val startStopId: String?,
    val endStopId: String?,
    val pathStartDate: String?,
    val pathEndDate: String?,
    val description: String?,
    val regionId: String
)
