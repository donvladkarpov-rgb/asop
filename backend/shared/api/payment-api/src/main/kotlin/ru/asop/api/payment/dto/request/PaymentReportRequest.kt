package ru.asop.api.payment.dto.request

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Отчёт app-payment о фактически проведённом платеже (offline-capable store-and-forward).
 *
 * Платёж на стороне АСОП считается проведённым после успешной обработки этого отчёта.
 */
data class PaymentReportRequest(
    @field:NotNull
    val paymentId: UUID,

    val requestId: UUID? = null,

    val cardToken: String? = null,

    @field:NotNull
    @field:DecimalMin(value = "0.01")
    val amount: BigDecimal,

    val currency: String = "RUB",

    @field:NotBlank
    val paymentType: String,

    val acqReference: String? = null,

    val rrn: String? = null,

    val authCode: String? = null,

    /** AUTHORIZED | DECLINED | FAILED | REVERSED | REFUNDED */
    @field:NotBlank
    val status: String,

    val errorCode: String? = null,

    val errorMessage: String? = null,

    val terminalId: UUID? = null,

    val sessionId: UUID? = null,

    val transactionId: UUID? = null,

    /** Время операции НА УСТРОЙСТВЕ (не приёма на сервере). */
    val occurredAt: Instant? = null
)
