package ru.asop.payment.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.payment.controller.PaymentApi
import ru.asop.api.payment.dto.request.PaymentAuthorizeRequest
import ru.asop.api.payment.dto.request.PaymentReportRequest
import ru.asop.api.payment.dto.response.PaymentResponse
import ru.asop.payment.service.PaymentService
import java.util.UUID

@RestController
class PaymentController(
    private val paymentService: PaymentService
) : PaymentApi {

    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) paymentType: String?,
        @RequestParam(required = false) terminalId: UUID?,
        @RequestParam(required = false, defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
        @RequestParam(required = false, defaultValue = "0") offset: Long
    ): Flux<PaymentResponse> =
        paymentService.list(status, paymentType, terminalId, includeDeleted, limit, offset)

    override fun authorize(request: PaymentAuthorizeRequest): Mono<ResponseEntity<PaymentResponse>> =
        paymentService.authorize(request)
            .map { ResponseEntity.ok(it) }

    override fun report(request: PaymentReportRequest): Mono<ResponseEntity<PaymentResponse>> =
        paymentService.report(request)
            .map { ResponseEntity.ok(it) }

    override fun getPayment(id: UUID): Mono<ResponseEntity<PaymentResponse>> =
        paymentService.get(id)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND).build())

    override fun reauthorize(id: UUID): Mono<ResponseEntity<PaymentResponse>> =
        paymentService.reauthorize(id)
            .map { ResponseEntity.ok(it) }
            .onErrorResume(IllegalArgumentException::class.java) {
                Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build())
            }

    override fun cancel(id: UUID): Mono<ResponseEntity<PaymentResponse>> =
        paymentService.cancel(id)
            .map { ResponseEntity.ok(it) }
            .onErrorResume(IllegalArgumentException::class.java) {
                Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build())
            }

    override fun refund(id: UUID): Mono<ResponseEntity<PaymentResponse>> =
        paymentService.refund(id)
            .map { ResponseEntity.ok(it) }
            .onErrorResume(IllegalArgumentException::class.java) {
                Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build())
            }
}
