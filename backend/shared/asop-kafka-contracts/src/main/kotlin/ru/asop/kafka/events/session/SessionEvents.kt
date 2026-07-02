package ru.asop.kafka.events.session

import ru.asop.common.event.BaseDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Событие открытия сессии.
 */
data class SessionOpenedEvent(
    val sessionId: UUID,
    val sessionTypeId: UUID,
    val terminalId: UUID?,
    val pathId: UUID?,
    val vehicleId: UUID?,
    val openedByUserId: UUID?,
    val startedAt: Instant,

    override val aggregateType: String = "Session",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = sessionId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)

/**
 * Событие закрытия сессии.
 */
data class SessionClosedEvent(
    val sessionId: UUID,
    val status: String,
    val closedByUserId: UUID?,
    val closedAt: Instant,

    override val aggregateType: String = "Session",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = sessionId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)