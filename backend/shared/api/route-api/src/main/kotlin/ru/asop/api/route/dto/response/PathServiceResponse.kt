package ru.asop.api.route.dto.response

data class PathServiceResponse(
    val id: String,
    val pathId: String,
    val serviceId: String,
    val carrierId: String?,
    val vehicleId: String?,
    val tariffTypeId: String?,
    val price: Double,
    val isActive: Boolean?
)
