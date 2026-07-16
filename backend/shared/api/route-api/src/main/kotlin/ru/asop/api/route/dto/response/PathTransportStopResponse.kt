package ru.asop.api.route.dto.response

data class PathTransportStopResponse(
    val id: String,
    val pathId: String,
    val stopId: String,
    val serialNumber: Int,
    val regionId: String
)
