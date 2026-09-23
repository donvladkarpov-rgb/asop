package ru.asop.payment.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Банковский платёж (prompt_016 §3.1.3).
 *
 * Статусы: PENDING | AUTHORIZED | DECLINED | FAILED | REVERSED | REFUNDED.
 * Типы: TOPUP | FARE | DEBT_RECOVERY.
 */
@Table("ASOP_BANK_PAYMENTS")
data class PaymentEntity(
    @Id
    val paymentId: UUID,

    /** Идемпотентный ключ от терминала/приложения (partial unique index). */
    val requestId: UUID? = null,

    /** Банковская карта АСОП (ASOP_CARDS.CARD_ID), если известна. */
    val cardId: UUID? = null,

    /** HMAC(PAN) / PAN_TOKEN — PAN в открытом виде не хранится. */
    val cardToken: String? = null,

    val panLast4: String? = null,
    val bin: String? = null,

    val amount: BigDecimal,
    val currency: String = "RUB",
    val paymentType: String,
    val status: String,
    val provider: String,

    val acquirerReference: String? = null,
    val rrn: String? = null,
    val authCode: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,

    val terminalId: UUID? = null,
    val sessionId: UUID? = null,
    val transactionId: UUID? = null,

    /** Время операции НА УСТРОЙСТВЕ (last-wins по нему). */
    val occurredAt: Instant? = null,

    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)
