package ru.asop.gateway.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kafka.sender.SenderResult
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.debt.DebtCreatedEvent
import ru.asop.kafka.events.debt.DebtRecoveredEvent
import ru.asop.api.debt.dto.request.DebtCreateRequest
import java.security.Principal
import java.time.Instant
import java.util.UUID

@Service
class DebtCommandService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val eventService: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(request: DebtCreateRequest, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val debtId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()

            val event = DebtCreatedEvent(
                debtId = debtId,
                cardId = request.cardId,
                transactionId = null,
                carrierId = request.carrierId,
                debtAmount = request.debtAmount,
                currency = "RUB",
                terminalId = request.terminalId,
                sessionId = request.sessionId,
                debtOpenedAt = Instant.now(),
                debtDueDate = Instant.now().plusSeconds(30 * 24 * 60 * 60),
                correlationId = correlationId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.DEBT_COMMANDS,
                event.debtId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.DEBT_COMMANDS)

            kafkaTemplate.send(record)
                .doOnSuccess { result: SenderResult<*> ->
                    log.info(
                        "Sent DebtCreated to topic={}, eventId={}, debtId={}",
                        KafkaTopic.DEBT_COMMANDS, eventId, event.debtId
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error")
                    log.error("Failed to send DebtCreated to Kafka: {}", error.message, error)
                }
                .thenReturn(eventId)
        }
    }

    fun recoverDebt(id: UUID, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()

            val event = DebtRecoveredEvent(
                debtId = id,
                cardId = null,
                recoveryTransactionId = UuidUtils.newId(),
                recoveredAt = Instant.now(),
                correlationId = correlationId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.DEBT_COMMANDS,
                event.debtId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.DEBT_COMMANDS)

            kafkaTemplate.send(record)
                .doOnSuccess { result: SenderResult<*> ->
                    log.info(
                        "Sent DebtRecovered to topic={}, eventId={}, debtId={}",
                        KafkaTopic.DEBT_COMMANDS, eventId, event.debtId
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error")
                    log.error("Failed to send DebtRecovered to Kafka: {}", error.message, error)
                }
                .thenReturn(eventId)
        }
    }
}
