package ru.asop.kafka.events.fiscal

import ru.asop.common.event.BaseDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Событие запроса фискализации чека.
 */
data class FiscalReceiptRequestedEvent(
    val receiptId: UUID,
    /** NULL для дистрибьюторских чеков и терминальных до резолва транзакции (промпт 017). */
    val transactionId: UUID?,
    val carrierId: UUID?,
    val carrierFiscalizerId: UUID?,
    val amount: java.math.BigDecimal,
    val description: String? = null,
    val sessionId: UUID? = null,
    val cardsDistributorId: UUID? = null,
    val source: String = "TERMINAL",
    /** Ссылка эквайрера (RRN/acqReference) — общий ключ дедупа с авто-контуром по payment-событию. */
    val acqReference: String? = null,
    val rrn: String? = null,
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