package ru.asop.gateway.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kafka.sender.SenderResult
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.fiscal.FiscalReceiptRequestedEvent
import ru.asop.api.fiscal.dto.request.FiscalReceiptRequest
import java.security.Principal
import java.time.Instant
import java.util.UUID

@Service
class FiscalCommandService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val eventService: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun requestReceipt(request: FiscalReceiptRequest, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val receiptId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()

            val event = FiscalReceiptRequestedEvent(
                receiptId = receiptId,
                transactionId = request.transactionId,
                carrierId = null,
                carrierFiscalizerId = null,
                amount = request.amount,
                description = request.description,
                requestedAt = Instant.now(),
                correlationId = correlationId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.FISCAL_COMMANDS,
                event.receiptId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.FISCAL_COMMANDS)
                .then(kafkaTemplate.send(record))
                .doOnSuccess { result: SenderResult<*> ->
                    log.info(
                        "Sent FiscalReceiptRequested to topic={}, eventId={}, receiptId={}",
                        KafkaTopic.FISCAL_COMMANDS, eventId, event.receiptId
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error").subscribe()
                    log.error("Failed to send FiscalReceiptRequested to Kafka: {}", error.message, error)
                }
                .thenReturn(eventId)
        }
    }
}
