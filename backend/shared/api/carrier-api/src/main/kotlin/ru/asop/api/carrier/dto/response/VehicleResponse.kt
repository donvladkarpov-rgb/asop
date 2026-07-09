package ru.asop.api.carrier.dto.response

import java.util.UUID

data class VehicleResponse(
    val id: UUID,
    val carrierId: UUID?,
    val vehicleTypeId: UUID,
    val vehicleModelId: UUID,
    val vehicleNumber: String,
    val vehicleName: String
)
