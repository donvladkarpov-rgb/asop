package ru.asop.payment.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * Попытка авторизации у эквайера (prompt_016 §3.1.3, легаси `bill_bank_operations_attempts`).
 *
 * Статусы: PENDING | SUCCESS | FAILED | TIMEOUT | REJECTED.
 */
@Table("ASOP_PAYMENT_ATTEMPTS")
data class PaymentAttemptEntity(
    @Id
    val attemptId: UUID,

    val paymentId: UUID,
    val attemptNumber: Int,
    val provider: String,
    val status: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,

    /** Синтетический ответ банка (JSON) — в моке без реальных PAN/ключей. */
    val bankResponse: String? = null,

    val durationMs: Int? = null,
    val nextRetryAt: Instant? = null,
    val createdAt: Instant = Instant.now()
)
