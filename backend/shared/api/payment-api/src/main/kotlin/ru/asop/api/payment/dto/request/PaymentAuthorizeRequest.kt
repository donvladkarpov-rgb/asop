package ru.asop.api.payment.dto.request

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

/**
 * Запрос авторизации банковского платежа (app-payment / app-distributor → payment-service).
 *
 * `cardToken` — уже токенизированный идентификатор карты (HMAC(PAN) или PAN_TOKEN из ASOP_CARD_BANKS).
 * PAN в открытом виде в payment-service НЕ передаётся.
 */
data class PaymentAuthorizeRequest(
    /** Идемпотентный ключ от терминала/приложения (повтор не создаёт дубль). */
    val requestId: UUID? = null,

    @field:NotNull
    @field:DecimalMin(value = "0.01")
    val amount: BigDecimal,

    val currency: String = "RUB",

    /** TOPUP | FARE | DEBT_RECOVERY */
    @field:NotBlank
    val paymentType: String,

    @field:NotBlank
    val cardToken: String,

    val panLast4: String? = null,

    val bin: String? = null,

    val terminalId: UUID? = null,

    val sessionId: UUID? = null,

    val transactionId: UUID? = null
)
