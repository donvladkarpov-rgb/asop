package ru.asop.payment.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.payment.dto.response.PaymentResponse
import ru.asop.payment.service.PaymentService

/**
 * Публичный callback статуса от эквайера (prompt_016 §3.1.5).
 * Gateway пробрасывает `POST /api/v1/public/payment-status` → `/api/v1/payment-status`.
 */
@RestController
@RequestMapping("/api/v1/payment-status")
class PaymentStatusCallbackController(
    private val paymentService: PaymentService
) {

    @PostMapping
    fun callback(@RequestBody callback: PaymentStatusCallback): Mono<ResponseEntity<PaymentResponse>> =
        paymentService.statusCallback(callback)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND).build())
}