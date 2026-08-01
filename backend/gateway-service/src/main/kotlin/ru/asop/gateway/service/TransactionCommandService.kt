package ru.asop.gateway.service

import ru.asop.common.event.EventService

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kafka.sender.SenderResult
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.transaction.TransactionCompletedEvent
import ru.asop.api.gateway.dto.request.TransactionCompleteRequest
import java.security.Principal
import java.time.Instant
import java.util.UUID

@Service
class TransactionCommandService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val eventService: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun complete(request: TransactionCompleteRequest, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val transactionId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()

            val event = TransactionCompletedEvent(
                transactionId = transactionId,
                sessionId = request.sessionId,
                transactionTypeId = request.transactionTypeId,
                transactionResultId = request.transactionResultId,
                amount = request.amount,
                currency = request.currency,
                cardId = request.cardId,
                metadata = request.metadata,
                completedAt = Instant.now(),
                correlationId = correlationId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.TRANSACTION_COMMANDS,
                event.transactionId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.TRANSACTION_COMMANDS)
                .then(kafkaTemplate.send(record))
                .doOnSuccess { result: SenderResult<*> ->
                    log.info(
                        "Sent TransactionCompleted to topic={}, eventId={}, transactionId={}",
                        KafkaTopic.TRANSACTION_COMMANDS, eventId, event.transactionId
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error").subscribe()
                    log.error("Failed to send TransactionCompleted to Kafka: {}", error.message, error)
                }
                .thenReturn(eventId)
        }
    }
}
