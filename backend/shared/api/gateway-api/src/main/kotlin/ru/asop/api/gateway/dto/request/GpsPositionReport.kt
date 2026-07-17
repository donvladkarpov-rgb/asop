package ru.asop.api.gateway.dto.request

import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class GpsPositionReport(
    @field:NotNull
    val vehicleId: UUID,

    @field:NotNull
    val pathId: UUID,

    val sessionId: UUID? = null,

    @field:NotNull
    val latitude: BigDecimal,

    @field:NotNull
    val longitude: BigDecimal,

    val speedKmh: BigDecimal? = null,

    val recordedAt: Instant = Instant.now()
)
