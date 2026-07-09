package ru.asop.kafka.events.transaction

import ru.asop.common.event.BaseDomainEvent
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Событие завершения транзакции.
 */
data class TransactionCompletedEvent(
    val transactionId: UUID,
    val sessionId: UUID?,
    val transactionTypeId: UUID,
    val transactionResultId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val completedAt: Instant,

    override val aggregateType: String = "Transaction",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = transactionId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)