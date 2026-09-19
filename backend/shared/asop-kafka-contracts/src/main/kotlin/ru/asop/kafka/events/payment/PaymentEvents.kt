package ru.asop.kafka.events.payment

import ru.asop.common.event.BaseDomainEvent
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Успешная авторизация банковского платежа.
 */
data class PaymentAuthorizedEvent(
    val paymentId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val paymentType: String,
    val provider: String,
    val acquirerReference: String?,
    val rrn: String?,
    val authCode: String?,
    val cardId: UUID? = null,
    val panLast4: String? = null,
    val terminalId: UUID? = null,
    val sessionId: UUID? = null,
    val transactionId: UUID? = null,

    override val aggregateType: String = "Payment",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = paymentId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)

/**
 * Неуспешный банковский платёж (decline / timeout / defer / reauth / duplicate).
 */
data class PaymentFailedEvent(
    val paymentId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val paymentType: String,
    val provider: String,
    val status: String,
    val errorCode: String?,
    val errorMessage: String?,
    val terminalId: UUID? = null,
    val sessionId: UUID? = null,
    val transactionId: UUID? = null,

    override val aggregateType: String = "Payment",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = paymentId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)
