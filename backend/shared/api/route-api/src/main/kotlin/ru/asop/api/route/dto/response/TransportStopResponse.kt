package ru.asop.api.route.dto.response

data class TransportStopResponse(
    val id: String,
    val stopCode: String,
    val stopName: String,
    val regionId: String,
    val fareZoneId: String?,
    val zonePolygon: String?,
    val stopAddress: String?,
    val description: String?,
    val isActive: Boolean
)
