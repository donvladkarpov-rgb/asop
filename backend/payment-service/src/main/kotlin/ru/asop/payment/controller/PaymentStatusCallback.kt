package ru.asop.payment.controller

import java.util.UUID

/**
 * Публичный callback статуса от эквайера (prompt_016 §3.1.5).
 * Доступен как `POST /api/v1/public/payment-status` через gateway `ApiKeyHmacFilter`;
 * внутри сервиса — `POST /api/v1/payment-status`.
 */
data class PaymentStatusCallback(
    /** Либо paymentId, либо acquirerReference — что эквайер вернул при аутентификации. */
    val paymentId: UUID? = null,
    val acquirerReference: String? = null,

    val status: String,

    val rrn: String? = null,
    val authCode: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
)