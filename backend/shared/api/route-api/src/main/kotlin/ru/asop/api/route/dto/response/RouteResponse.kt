package ru.asop.api.route.dto.response

data class RouteResponse(
    val id: String,
    val routeNumber: String,
    val routeName: String,
    val routeCategory: String,
    val organizerId: String?,
    val ministryRegistryNo: String?,
    val regionId: String
)
