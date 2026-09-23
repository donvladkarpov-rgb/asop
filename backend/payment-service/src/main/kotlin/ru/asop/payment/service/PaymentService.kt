package ru.asop.payment.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import io.r2dbc.spi.Row
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import ru.asop.api.payment.dto.request.PaymentAuthorizeRequest
import ru.asop.api.payment.dto.request.PaymentReportRequest
import ru.asop.api.payment.dto.response.PaymentResponse
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.payment.PaymentAuthorizedEvent
import ru.asop.kafka.events.payment.PaymentFailedEvent
import ru.asop.payment.acquirer.AcquirerAuthorizeRequest
import ru.asop.payment.acquirer.AcquirerAuthResult
import ru.asop.payment.acquirer.AcquirerGateway
import ru.asop.payment.controller.PaymentStatusCallback
import ru.asop.payment.kafka.PaymentEventPublisher
import ru.asop.payment.model.PaymentAttemptEntity
import ru.asop.payment.model.PaymentEntity
import ru.asop.payment.repository.PaymentAttemptRepository
import ru.asop.payment.repository.PaymentRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class PaymentService(
    private val payments: PaymentRepository,
    private val attempts: PaymentAttemptRepository,
    private val template: R2dbcEntityTemplate,
    private val db: DatabaseClient,
    private val acquirer: AcquirerGateway,
    private val publisher: PaymentEventPublisher,
    private val objectMapper: ObjectMapper,
    private val acquirerProperties: ru.asop.payment.acquirer.AcquirerProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(
        status: String? = null,
        paymentType: String? = null,
        terminalId: UUID? = null,
        includeDeleted: Boolean = false,
        limit: Int = 100,
        offset: Long = 0
    ): Flux<PaymentResponse> {
        val sql = StringBuilder("SELECT * FROM ASOP_BANK_PAYMENTS WHERE 1=1")
        if (status != null) sql.append(" AND STATUS = :status")
        if (paymentType != null) sql.append(" AND PAYMENT_TYPE = :paymentType")
        if (terminalId != null) sql.append(" AND TERMINAL_ID = :terminalId")
        if (!includeDeleted) sql.append(" AND DELETED_AT IS NULL")
        sql.append(" ORDER BY CREATED_AT DESC LIMIT :limit OFFSET :offset")

        val spec = db.sql(sql.toString())
            .let { s -> if (status != null) s.bind("status", status) else s }
            .let { s -> if (paymentType != null) s.bind("paymentType", paymentType) else s }
            .let { s -> if (terminalId != null) s.bind("terminalId", terminalId) else s }
            .bind("limit", limit)
            .bind("offset", offset)

        return spec.map { row, _ -> toResponse(row.toEntity()) }.all()
    }

    fun authorize(request: PaymentAuthorizeRequest): Mono<PaymentResponse> {
        val existing = request.requestId?.let { payments.findByRequestId(it) } ?: Mono.empty()
        return existing.flatMap {
            log.info("Idempotent payment authorize: requestId={} -> paymentId={}", request.requestId, it.paymentId)
            Mono.just(toResponse(it))
        }.switchIfEmpty(doAuthorize(request))
    }

    private fun doAuthorize(request: PaymentAuthorizeRequest): Mono<PaymentResponse> {
        val paymentId = UuidUtils.newId()
        val payment = PaymentEntity(
            paymentId = paymentId,
            requestId = request.requestId,
            cardToken = request.cardToken,
            panLast4 = request.panLast4,
            bin = request.bin,
            amount = request.amount,
            currency = request.currency,
            paymentType = request.paymentType,
            status = "PENDING",
            provider = acquirer.provider,
            terminalId = request.terminalId,
            sessionId = request.sessionId,
            transactionId = request.transactionId,
            occurredAt = Instant.now()
        )
        return template.insert(payment)
            .then(template.insert(attempts.newAttempt(paymentId, 1, "PENDING")))
            .flatMap { acquirer.authorize(toAcquirerRequest(paymentId, request)) }
            .flatMap { result -> persistResult(payment, result) }
            .doOnError { e -> log.error("Authorize failed: paymentId={}, error={}", paymentId, e.message) }
    }

    fun report(request: PaymentReportRequest): Mono<PaymentResponse> =
        payments.findActiveById(request.paymentId)
            .flatMap { existing -> updateFromReport(existing, request) }
            .switchIfEmpty(createFromReport(request))

    fun get(paymentId: UUID): Mono<PaymentResponse> =
        payments.findActiveById(paymentId).map { toResponse(it) }

    /** Повторная авторизация (REAUTH_REQUIRED). Идемпотентна по статусу. */
    fun reauthorize(paymentId: UUID): Mono<PaymentResponse> =
        payments.findActiveById(paymentId)
            .filter { it.status in setOf("PENDING", "AUTHORIZED") }
            .flatMap { payment ->
                acquirer.reauthorize(payment.paymentId, payment.amount)
                    .flatMap { result -> persistResult(payment, result) }
            }
            .switchIfEmpty(Mono.error(IllegalArgumentException("Событие недоступно for reauthorize (payment not found or wrong status).")))

    /** Отмена (void) авторизации: PENDING/AUTHORIZED → REVERSED. */
    fun cancel(paymentId: UUID): Mono<PaymentResponse> =
        transitions(paymentId, setOf("PENDING", "AUTHORIZED")) { payment ->
            acquirer.cancel(payment.paymentId, payment.amount)
        }

    /** Возврат (refund) авторизованного платежа: AUTHORIZED → REFUNDED. */
    fun refund(paymentId: UUID): Mono<PaymentResponse> =
        transitions(paymentId, setOf("AUTHORIZED")) { payment ->
            acquirer.refund(payment.paymentId, payment.amount)
        }

    /** Статус у эквайера (сверка). Возвращает текущий сохранённый статус + ответ эквайера. */
    fun status(paymentId: UUID): Mono<PaymentResponse> =
        payments.findActiveById(paymentId)
            .flatMap { payment ->
                acquirer.status(payment.paymentId, payment.acquirerReference)
                    .map { result ->
                        toResponse(payment).copy(
                            status = result.status,
                            errorCode = result.errorCode,
                            errorMessage = result.errorMessage
                        )
                    }
            }
            .switchIfEmpty(Mono.error(IllegalArgumentException("Платёж не найден: $paymentId")))

    // ------------------------------------------------------------------ internals

    /** Публичный callback от эквайера: матчинг по paymentId ИЛИ acquirerReference. */
    fun statusCallback(callback: PaymentStatusCallback): Mono<PaymentResponse> {
        val lookup = callback.paymentId
            ?.let { payments.findActiveById(it) }
            ?: callback.acquirerReference
                ?.takeIf { it.isNotBlank() }
                ?.let { payments.findByAcquirerReference(it) }
                ?: Mono.empty()
        return lookup.flatMap { existing ->
            val becameAuthorized = callback.status == "AUTHORIZED" && existing.status != "AUTHORIZED"
            val updated = existing.copy(
                status = callback.status,
                acquirerReference = callback.acquirerReference ?: existing.acquirerReference,
                rrn = callback.rrn ?: existing.rrn,
                authCode = callback.authCode ?: existing.authCode,
                errorCode = callback.errorCode,
                errorMessage = callback.errorMessage,
                updatedAt = Instant.now()
            )
            log.info(
                "Payment status callback: paymentId={}, status={} (was {}), ref={}",
                updated.paymentId, updated.status, existing.status, updated.acquirerReference
            )
            template.update(updated)
                .then(
                    if (becameAuthorized) publishEvents(updated, authorizedResult(updated)) else Mono.empty()
                )
                .thenReturn(toResponse(updated))
        }
    }

    // ------------------------------------------------------------------ internals

    private fun persistResult(payment: PaymentEntity, result: AcquirerAuthResult): Mono<PaymentResponse> {
        val updated = payment.copy(
            status = paymentStatus(result),
            acquirerReference = result.acquirerReference,
            rrn = result.rrn,
            authCode = result.authCode,
            errorCode = result.errorCode,
            errorMessage = result.errorMessage,
            updatedAt = Instant.now()
        )
        val attemptStatus = attemptStatus(result)
        val nextRetryAt = if (result.status == "TIMEOUT") {
            Instant.now().plusSeconds(acquirerProperties.mock.timeoutNextRetrySeconds)
        } else {
            null
        }
        return template.update(updated)
            .then(
                attempts.findByPaymentIdOrderByAttemptNumberAsc(payment.paymentId).next()
                    .flatMap { attempt ->
                        template.update(
                            attempt.copy(
                                status = attemptStatus,
                                errorCode = result.errorCode,
                                errorMessage = result.errorMessage,
                                bankResponse = bankResponse(result),
                                durationMs = result.durationMs.toInt(),
                                nextRetryAt = nextRetryAt
                            )
                        )
                    }
            )
            .then(publishEvents(updated, result))
            .thenReturn(toResponse(updated))
    }

    private fun publishEvents(payment: PaymentEntity, result: AcquirerAuthResult): Mono<Void> {
        val mono = if (result.approved) {
            publisher.publishAuthorized(
                PaymentAuthorizedEvent(
                    paymentId = payment.paymentId,
                    amount = payment.amount,
                    currency = payment.currency,
                    paymentType = payment.paymentType,
                    provider = payment.provider,
                    acquirerReference = payment.acquirerReference,
                    rrn = payment.rrn,
                    authCode = payment.authCode,
                    terminalId = payment.terminalId,
                    sessionId = payment.sessionId,
                    transactionId = payment.transactionId
                ),
                null
            )
        } else {
            publisher.publishFailed(
                PaymentFailedEvent(
                    paymentId = payment.paymentId,
                    amount = payment.amount,
                    currency = payment.currency,
                    paymentType = payment.paymentType,
                    provider = payment.provider,
                    status = payment.status,
                    errorCode = payment.errorCode,
                    errorMessage = payment.errorMessage,
                    terminalId = payment.terminalId,
                    sessionId = payment.sessionId,
                    transactionId = payment.transactionId
                ),
                null
            )
        }
        return mono.onErrorResume { e ->
            log.warn("Payment event publish failed (non-fatal): paymentId={}, error={}", payment.paymentId, e.message)
            Mono.empty()
        }
    }

    private fun updateFromReport(existing: PaymentEntity, request: PaymentReportRequest): Mono<PaymentResponse> {
        val updated = existing.copy(
            cardToken = request.cardToken ?: existing.cardToken,
            amount = request.amount,
            currency = request.currency,
            paymentType = request.paymentType,
            status = request.status,
            acquirerReference = request.acqReference ?: existing.acquirerReference,
            rrn = request.rrn ?: existing.rrn,
            authCode = request.authCode ?: existing.authCode,
            errorCode = request.errorCode,
            errorMessage = request.errorMessage,
            terminalId = request.terminalId ?: existing.terminalId,
            sessionId = request.sessionId ?: existing.sessionId,
            transactionId = request.transactionId ?: existing.transactionId,
            occurredAt = request.occurredAt ?: existing.occurredAt,
            updatedAt = Instant.now()
        )
        log.info("Payment report update: paymentId={}, status={}", updated.paymentId, updated.status)
        return template.update(updated).thenReturn(toResponse(updated))
    }

    private fun createFromReport(request: PaymentReportRequest): Mono<PaymentResponse> {
        val payment = PaymentEntity(
            paymentId = request.paymentId,
            requestId = request.requestId,
            cardToken = request.cardToken,
            amount = request.amount,
            currency = request.currency,
            paymentType = request.paymentType,
            status = request.status,
            provider = acquirer.provider,
            acquirerReference = request.acqReference,
            rrn = request.rrn,
            authCode = request.authCode,
            errorCode = request.errorCode,
            errorMessage = request.errorMessage,
            terminalId = request.terminalId,
            sessionId = request.sessionId,
            transactionId = request.transactionId,
            occurredAt = request.occurredAt ?: Instant.now()
        )
        log.info("Payment report insert: paymentId={}, status={}", payment.paymentId, payment.status)
        return template.insert(payment).map { toResponse(it) }
    }

    private fun PaymentAttemptRepository.newAttempt(
        paymentId: UUID,
        number: Int,
        status: String
    ): PaymentAttemptEntity = PaymentAttemptEntity(
        attemptId = UuidUtils.newId(),
        paymentId = paymentId,
        attemptNumber = number,
        provider = acquirer.provider,
        status = status
    )

    /** Общий хелпер cancel/refund: guard по статусу → вызов эквайера → статус=result.status + новая attempt. */
    private fun transitions(
        paymentId: UUID,
        allowedStatuses: Set<String>,
        call: (PaymentEntity) -> Mono<AcquirerAuthResult>
    ): Mono<PaymentResponse> =
        payments.findActiveById(paymentId)
            .filter { it.status in allowedStatuses }
            .flatMap { payment ->
                attempts.findByPaymentIdOrderByAttemptNumberAsc(payment.paymentId)
                    .map { it.attemptNumber }
                    .last()
                    .defaultIfEmpty(0)
                    .flatMap { lastAttempt ->
                        call(payment).flatMap { result ->
                            val updated = payment.copy(
                                status = result.status,
                                acquirerReference = result.acquirerReference ?: payment.acquirerReference,
                                rrn = result.rrn ?: payment.rrn,
                                authCode = result.authCode ?: payment.authCode,
                                errorCode = result.errorCode,
                                errorMessage = result.errorMessage,
                                updatedAt = Instant.now()
                            )
                            template.update(updated)
                                .then(template.insert(attempts.newAttempt(paymentId, lastAttempt + 1, attemptStatus(result))))
                                .thenReturn(toResponse(updated))
                        }
                    }
            }
            .switchIfEmpty(Mono.error(IllegalArgumentException("Transition not allowed for payment $paymentId")))

    private fun paymentStatus(result: AcquirerAuthResult): String = when (result.status) {
        "AUTHORIZED" -> "AUTHORIZED"
        "DECLINED" -> "DECLINED"
        "DEFERRED", "REAUTH_REQUIRED" -> "PENDING"
        else -> "FAILED"
    }

    /** Синтетический AUTHORIZED-результат для публикации события по public callback. */
    private fun authorizedResult(payment: PaymentEntity) = AcquirerAuthResult(
        approved = true,
        status = "AUTHORIZED",
        acquirerReference = payment.acquirerReference,
        rrn = payment.rrn,
        authCode = payment.authCode,
        durationMs = 0
    )

    private fun attemptStatus(result: AcquirerAuthResult): String = when (result.status) {
        "AUTHORIZED" -> "SUCCESS"
        "DECLINED", "DUPLICATE" -> "REJECTED"
        "TIMEOUT" -> "TIMEOUT"
        "DEFERRED", "REAUTH_REQUIRED" -> "PENDING"
        else -> "FAILED"
    }

    private fun bankResponse(result: AcquirerAuthResult): String =
        runCatching {
            objectMapper.writeValueAsString(
                mapOf(
                    "provider" to acquirer.provider,
                    "status" to result.status,
                    "approved" to result.approved,
                    "deferred" to result.deferred,
                    "acquirerReference" to result.acquirerReference,
                    "rrn" to result.rrn,
                    "authCode" to result.authCode,
                    "errorCode" to result.errorCode,
                    "errorMessage" to result.errorMessage
                )
            )
        }.getOrDefault("{}")

    private fun toAcquirerRequest(paymentId: UUID, request: PaymentAuthorizeRequest) = AcquirerAuthorizeRequest(
        paymentId = paymentId,
        amount = request.amount,
        currency = request.currency,
        cardToken = request.cardToken,
        panLast4 = request.panLast4,
        paymentType = request.paymentType
    )

    private fun Row.toEntity() = PaymentEntity(
        paymentId = requireNotNull(get("payment_id", UUID::class.java)),
        requestId = get("request_id", UUID::class.java),
        cardId = get("card_id", UUID::class.java),
        cardToken = get("card_token", String::class.java),
        panLast4 = get("pan_last4", String::class.java),
        bin = get("bin", String::class.java),
        amount = requireNotNull(get("amount", java.math.BigDecimal::class.java)),
        currency = requireNotNull(get("currency", String::class.java)),
        paymentType = requireNotNull(get("payment_type", String::class.java)),
        status = requireNotNull(get("status", String::class.java)),
        provider = requireNotNull(get("provider", String::class.java)),
        acquirerReference = get("acquirer_reference", String::class.java),
        rrn = get("rrn", String::class.java),
        authCode = get("auth_code", String::class.java),
        errorCode = get("error_code", String::class.java),
        errorMessage = get("error_message", String::class.java),
        terminalId = get("terminal_id", UUID::class.java),
        sessionId = get("session_id", UUID::class.java),
        transactionId = get("transaction_id", UUID::class.java),
        occurredAt = get("occurred_at", java.time.Instant::class.java),
        createdAt = requireNotNull(get("created_at", java.time.Instant::class.java)),
        updatedAt = requireNotNull(get("updated_at", java.time.Instant::class.java)),
        deletedAt = get("deleted_at", java.time.Instant::class.java),
        version = get("version", java.lang.Long::class.java)?.toLong()
    )

    private fun toResponse(entity: PaymentEntity) = PaymentResponse(
        paymentId = entity.paymentId,
        requestId = entity.requestId,
        status = entity.status,
        amount = entity.amount,
        currency = entity.currency,
        paymentType = entity.paymentType,
        provider = entity.provider,
        acquirerReference = entity.acquirerReference,
        rrn = entity.rrn,
        authCode = entity.authCode,
        errorCode = entity.errorCode,
        errorMessage = entity.errorMessage,
        panLast4 = entity.panLast4,
        terminalId = entity.terminalId,
        sessionId = entity.sessionId,
        transactionId = entity.transactionId,
        occurredAt = entity.occurredAt,
        createdAt = entity.createdAt
    )
}
