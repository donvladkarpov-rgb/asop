package ru.asop.kafka.events.fiscal

import ru.asop.common.event.BaseDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Событие запроса фискализации чека.
 */
data class FiscalReceiptRequestedEvent(
    val receiptId: UUID,
    val transactionId: UUID,
    val carrierId: UUID?,
    val carrierFiscalizerId: UUID?,
    val amount: java.math.BigDecimal,
    val description: String? = null,
    val requestedAt: Instant,

    override val aggregateType: String = "FiscalReceipt",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = receiptId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)

/**
 * Событие успешной фискализации чека.
 */
data class FiscalReceiptConfirmedEvent(
    val receiptId: UUID,
    val transactionId: UUID,
    val receiptNumber: String?,
    val fiscalSign: String?,
    val confirmedAt: Instant,

    override val aggregateType: String = "FiscalReceipt",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = receiptId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)