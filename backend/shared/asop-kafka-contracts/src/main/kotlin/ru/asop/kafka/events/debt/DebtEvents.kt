package ru.asop.kafka.events.debt

import ru.asop.common.event.BaseDomainEvent
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Событие создания долга по карте.
 */
data class DebtCreatedEvent(
    val debtId: UUID,
    val cardId: UUID,
    val transactionId: UUID?,
    val sessionId: UUID?,
    val terminalId: UUID?,
    val carrierId: UUID,
    val debtAmount: BigDecimal,
    val currency: String,
    val debtOpenedAt: Instant,
    val debtDueDate: Instant,

    override val aggregateType: String = "CardDebt",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = debtId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)

/**
 * Событие погашения долга.
 */
data class DebtRecoveredEvent(
    val debtId: UUID,
    val cardId: UUID,
    val recoveryTransactionId: UUID,
    val recoveredAt: Instant,

    override val aggregateType: String = "CardDebt",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = debtId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)