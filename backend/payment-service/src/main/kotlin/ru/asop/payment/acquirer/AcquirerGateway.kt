package ru.asop.payment.acquirer

import reactor.core.publisher.Mono
import java.math.BigDecimal
import java.util.UUID

/**
 * Абстракция эквайера. Единственное место, где АСОП взаимодействует с банком.
 *
 * Реализации:
 *  - [MockAcquirerAdapter] — детерминированный мок (provider=mock, dev/test);
 *  - [VtbSirposAdapter]    — ВТБ/SIRPOS (provider=vtb, интерфейс TBD).
 *
 * Терминал/приложение не ходят в банк напрямую — только через payment-service.
 */
interface AcquirerGateway {

    val provider: String

    /** Авторизация платежа. */
    fun authorize(request: AcquirerAuthorizeRequest): Mono<AcquirerAuthResult>

    /** Повторная авторизация (легаси-коды 76/95/82, `allowReAuthErrCodes`). */
    fun reauthorize(paymentId: UUID, amount: BigDecimal): Mono<AcquirerAuthResult>

    /** Отмена (void) ранее авторизованного, но не захваченного платежа. */
    fun cancel(paymentId: UUID, amount: BigDecimal): Mono<AcquirerAuthResult>

    /** Возврат (refund) ранее авторизованного платежа. */
    fun refund(paymentId: UUID, amount: BigDecimal): Mono<AcquirerAuthResult>

    /** Статус платежа у эквайера (по paymentId или acquirerReference) — для сверки. */
    fun status(paymentId: UUID, acquirerReference: String?): Mono<AcquirerAuthResult>
}

data class AcquirerAuthorizeRequest(
    val paymentId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val cardToken: String,
    val panLast4: String? = null,
    val paymentType: String = "TOPUP"
)

/**
 * Результат авторизации у эквайера.
 *
 * @param approved true — авторизация успешна (в т.ч. offline floor limit).
 * @param status   AUTHORIZED | DECLINED | TIMEOUT | DEFERRED | REAUTH_REQUIRED | DUPLICATE
 * @param deferred true — отложенная авторизация (легаси-код 74/811, `allowReDefferErrCodes`).
 */
data class AcquirerAuthResult(
    val approved: Boolean,
    val status: String,
    val acquirerReference: String? = null,
    val rrn: String? = null,
    val authCode: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val deferred: Boolean = false,
    val durationMs: Long = 0
)
