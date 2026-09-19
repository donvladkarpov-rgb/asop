package ru.asop.fiscal.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import ru.asop.common.util.UuidUtils
import ru.asop.fiscal.model.FiscalReceiptEntity
import ru.asop.fiscal.repository.FiscalReceiptRepository
import ru.asop.fiscal.service.FiscalContextResolver
import ru.asop.kafka.events.payment.PaymentAuthorizedEvent
import java.time.Instant
import java.util.UUID

/**
 * Промпт 016 §3.1.7: успешный банковский платёж (TOPUP/FARE) → фискальный чек.
 *
 * Правило атрибуции: платёж вешается на TID, который водитель вводит при открытии рейса
 * (sessionId платёжного события → ASOP_SESSIONS.TID_ID → BANK-договор → перевозчик). Чек
 * создаётся только когда: есть transactionId (фискальный чек привязан к транзакции),
 * сессия имеет TID, TID привязан к актуальному BANK-договору с активным фискализатором.
 */
@Component
class PaymentReceiptConsumer(
    private val objectMapper: ObjectMapper,
    private val resolver: FiscalContextResolver,
    private val template: R2dbcEntityTemplate,
    private val receipts: FiscalReceiptRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.payment-events}"])
    fun onPaymentEvent(json: String) {
        try {
            val node = objectMapper.readTree(json)
            when (node.get("eventType")?.asText()) {
                "PaymentAuthorized" -> {
                    val event = objectMapper.treeToValue(node, PaymentAuthorizedEvent::class.java)
                    handleAuthorized(event)
                }
                else -> log.debug("Ignore payment event type: {}", node.get("eventType")?.asText())
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize payment event: {}", e.message, e)
        }
    }

    private fun handleAuthorized(event: PaymentAuthorizedEvent) {
        val transactionId = event.transactionId ?: run {
            log.debug("Payment {} has no transactionId - no fiscal receipt (sessionId={})",
                event.paymentId, event.sessionId)
            return
        }
        val sessionId = event.sessionId ?: run {
            log.debug("Payment {} has no sessionId - cannot resolve TID/carrier, skip fiscal receipt",
                event.paymentId)
            return
        }

        receipts.findByTransactionId(transactionId)
            .switchIfEmpty(
                resolver.resolve(sessionId)
                    .mapNotNull { ctx -> ctx }
                    .flatMap { ctx -> insertReceipt(event, transactionId, ctx) }
            )
            .subscribe(
                { _ -> log.debug("Fiscal receipt processed for transaction {}", transactionId) },
                { e ->
                    log.warn("Fiscal receipt processing failed for payment {}: {}",
                        event.paymentId, e.message)
                }
            )
    }

    private fun insertReceipt(
        event: PaymentAuthorizedEvent,
        transactionId: UUID,
        ctx: FiscalContextResolver.FiscalContext
    ): Mono<FiscalReceiptEntity> =
        template.insert(
            FiscalReceiptEntity(
                receiptId = UuidUtils.newId(),
                transactionId = transactionId,
                carrierId = ctx.carrierId,
                carrierFiscalizerId = ctx.carrierFiscalizerId,
                status = "PENDING",
                attemptCount = 0,
                maxAttempts = 10,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )
            .subscribeOn(Schedulers.boundedElastic())
            .doOnSuccess { entity ->
                log.info(
                    "Fiscal receipt created: receiptId={}, transactionId={}, paymentId={}, carrierId={}, fiscalizerId={}",
                    entity.receiptId, transactionId, event.paymentId, entity.carrierId, entity.carrierFiscalizerId
                )
            }
}