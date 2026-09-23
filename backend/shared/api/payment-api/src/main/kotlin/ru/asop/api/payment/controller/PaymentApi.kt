package ru.asop.api.payment.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.payment.dto.request.PaymentAuthorizeRequest
import ru.asop.api.payment.dto.request.PaymentReportRequest
import ru.asop.api.payment.dto.response.PaymentResponse
import java.util.UUID

/**
 * Контракт банковского эквайринга. Два пути (один и тот же сервис):
 *  - `/api/v1/payments` — JWT-контур (web-admin список/мониторинг через gateway);
 *  - `/api/v1/payment`  — mTLS-контур app-payment/app-distributor (authorize/report/re-auth/cancel/refund).
 *
 * Реальная авторизация у эквайера ВТБ выполняется на сервере (`AcquirerGateway`),
 * устройство PAN в открытом виде не передаёт.
 */
@RequestMapping(value = ["/api/v1/payments", "/api/v1/payment"])
interface PaymentApi {

    /** Авторизация платежа (топ-ап / проезд / погашение долга). */
    @PostMapping("/authorize")
    fun authorize(@Valid @RequestBody request: PaymentAuthorizeRequest): Mono<ResponseEntity<PaymentResponse>>

    /** Отчёт о фактически проведённом платеже (идемпотентно по paymentId). */
    @PostMapping("/report")
    fun report(@Valid @RequestBody request: PaymentReportRequest): Mono<ResponseEntity<PaymentResponse>>

    /** Повторная авторизация (легаси `allowReAuthErrCodes` = 76/95/82). */
    @PostMapping("/{id}/reauthorize")
    fun reauthorize(@PathVariable id: UUID): Mono<ResponseEntity<PaymentResponse>>

    /** Отмена (void) не захваченной авторизации. */
    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: UUID): Mono<ResponseEntity<PaymentResponse>>

    /** Возврат (refund) ранее авторизованного платежа. */
    @PostMapping("/{id}/refund")
    fun refund(@PathVariable id: UUID): Mono<ResponseEntity<PaymentResponse>>

    @GetMapping("/{id}")
    fun getPayment(@PathVariable id: UUID): Mono<ResponseEntity<PaymentResponse>>
}
