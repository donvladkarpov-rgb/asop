package ru.asop.kafka.events.gps

import ru.asop.common.event.BaseDomainEvent
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class GpsPositionReported(
    val positionId: UUID? = null,
    val vehicleId: UUID,
    val pathId: UUID,
    val sessionId: UUID?,
    val latitude: BigDecimal,
    val longitude: BigDecimal,
    val speedKmh: BigDecimal?,
    val recordedAt: Instant,

    override val aggregateType: String = "GpsTracking",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = positionId ?: UUID.randomUUID(),
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)
