package ru.asop.kafka.events.session

import ru.asop.common.event.BaseDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Событие открытия сессии. Промпт 011: расширено parentSessionId, tidId, cardId, attributes.
 */
data class SessionOpenedEvent(
    val sessionId: UUID,
    val sessionTypeId: UUID,
    val parentSessionId: UUID? = null,
    val terminalId: UUID? = null,
    val tidId: UUID? = null,
    val pathId: UUID? = null,
    val vehicleId: UUID? = null,
    val openedByUserId: UUID? = null,
    val cardId: UUID? = null,
    val startedAt: Instant,
    val attributes: String? = null,    // JSON: {carrierId, regionId, timezone, ...}

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
 * Событие закрытия сессии. Промпт 011: расширено closedByUserId.
 */
data class SessionClosedEvent(
    val sessionId: UUID,
    val status: String = "CLOSED",
    val closedByUserId: UUID? = null,
    val reason: String? = null,
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