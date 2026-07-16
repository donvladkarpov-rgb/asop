package ru.asop.api.route.dto.response

data class FareZoneResponse(
    val id: String,
    val zoneCode: String,
    val zoneName: String,
    val description: String?,
    val zonePolygon: String?,
    val regionId: String
)
