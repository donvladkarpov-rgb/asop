package ru.asop.api.payment.dto.response

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class PaymentResponse(
    val paymentId: UUID,
    val requestId: UUID? = null,
    val status: String,
    val amount: BigDecimal,
    val currency: String,
    val paymentType: String,
    val provider: String,
    val acquirerReference: String? = null,
    val rrn: String? = null,
    val authCode: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val panLast4: String? = null,
    val terminalId: UUID? = null,
    val sessionId: UUID? = null,
    val transactionId: UUID? = null,
    val occurredAt: Instant? = null,
    val createdAt: Instant? = null
)
