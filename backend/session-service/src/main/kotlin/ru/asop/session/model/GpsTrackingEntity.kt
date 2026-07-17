package ru.asop.session.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("ASOP_GPS_TRACKING")
data class GpsTrackingEntity(
    @Id
    val positionId: UUID,
    val vehicleId: UUID,
    val pathId: UUID,
    val sessionId: UUID? = null,
    val gpsCoord: String? = null,
    val recordedAt: Instant,
    val speedKmh: BigDecimal? = null,
    val status: String = "MOVING"
)
