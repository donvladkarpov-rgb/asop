package ru.asop.gateway.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kafka.sender.SenderResult
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.session.SessionOpenedEvent
import ru.asop.kafka.events.session.SessionClosedEvent
import ru.asop.api.session.dto.request.SessionOpenRequest
import ru.asop.api.session.dto.request.SessionCloseRequest
import java.security.Principal
import java.time.Instant
import java.util.UUID

@Service
class SessionCommandService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val eventService: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun openSession(request: SessionOpenRequest, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val sessionId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()

            val event = SessionOpenedEvent(
                sessionId = sessionId,
                sessionTypeId = request.sessionTypeId,
                terminalId = request.terminalId,
                pathId = request.pathId,
                vehicleId = request.vehicleId,
                openedByUserId = null,
                startedAt = Instant.now(),
                correlationId = correlationId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.SESSION_COMMANDS,
                event.sessionId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.SESSION_COMMANDS)
                .then(kafkaTemplate.send(record))
                .doOnSuccess { result: SenderResult<*> ->
                    log.info(
                        "Sent SessionOpened to topic={}, eventId={}, sessionId={}",
                        KafkaTopic.SESSION_COMMANDS, eventId, event.sessionId
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error").subscribe()
                    log.error("Failed to send SessionOpened to Kafka: {}", error.message, error)
                }
                .thenReturn(eventId)
        }
    }

    fun closeSession(id: UUID, request: SessionCloseRequest, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()

            val event = SessionClosedEvent(
                sessionId = id,
                status = "CLOSED",
                reason = request.reason,
                closedAt = Instant.now(),
                correlationId = correlationId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.SESSION_COMMANDS,
                event.sessionId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.SESSION_COMMANDS)
                .then(kafkaTemplate.send(record))
                .doOnSuccess { result: SenderResult<*> ->
                    log.info(
                        "Sent SessionClosed to topic={}, eventId={}, sessionId={}",
                        KafkaTopic.SESSION_COMMANDS, eventId, event.sessionId
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error").subscribe()
                    log.error("Failed to send SessionClosed to Kafka: {}", error.message, error)
                }
                .thenReturn(eventId)
        }
    }
}
