package ru.asop.api.carrier.dto.response

import java.time.Instant
import java.util.UUID

data class CarrierResponse(
    val id: UUID,
    val carrierName: String,
    val inn: String,
    val regionId: UUID,
    val createdAt: Instant,
    val updatedAt: Instant
)
