package ru.asop.api.route.dto.response

data class VehicleResponse(
    val id: String,
    val carrierId: String?,
    val vehicleTypeId: String,
    val vehicleModelId: String,
    val vehicleNumber: String,
    val vehicleName: String
)
