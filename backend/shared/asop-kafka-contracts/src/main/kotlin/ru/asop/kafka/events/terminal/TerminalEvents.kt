package ru.asop.kafka.events.terminal

import ru.asop.common.event.BaseDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Событие регистрации терминала.
 */
data class TerminalRegisteredEvent(
    val terminalId: UUID,
    val terminalSerial: String,
    val terminalNumber: String?,
    val terminalModel: String?,
    val carrierId: UUID?,
    val status: String,
    val registeredAt: Instant,

    override val aggregateType: String = "Terminal",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = terminalId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)

/**
 * Событие изменения статуса терминала.
 */
data class TerminalStatusChangedEvent(
    val terminalId: UUID,
    val oldStatus: String,
    val newStatus: String,
    val changedAt: Instant,

    override val aggregateType: String = "Terminal",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = terminalId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)