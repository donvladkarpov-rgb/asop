package ru.asop.gateway.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kafka.sender.SenderResult
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.card.CardRegisteredEvent
import ru.asop.kafka.events.card.CardBlockedEvent
import ru.asop.api.card.dto.request.CardRegisterRequest
import ru.asop.api.card.dto.request.CardBlockRequest
import java.security.Principal
import java.time.Instant
import java.util.UUID

@Service
class CardCommandService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val eventService: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun register(request: CardRegisterRequest, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val cardId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()

            val event = CardRegisteredEvent(
                cardId = cardId,
                cardTypeId = request.cardTypeId,
                ownerUserId = request.userId,
                isPrimary = false,
                registeredAt = Instant.now(),
                correlationId = correlationId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.CARD_COMMANDS,
                event.cardId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.CARD_COMMANDS)

            kafkaTemplate.send(record)
                .doOnSuccess { result: SenderResult<*> ->
                    log.info(
                        "Sent CardRegistered to topic={}, eventId={}, cardId={}",
                        KafkaTopic.CARD_COMMANDS, eventId, event.cardId
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error")
                    log.error("Failed to send CardRegistered to Kafka: {}", error.message, error)
                }
                .thenReturn(eventId)
        }
    }

    fun blockCard(id: UUID, request: CardBlockRequest, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()

            val event = CardBlockedEvent(
                cardId = id,
                blockType = request.blockType,
                reason = request.reason,
                blockedAt = Instant.now(),
                correlationId = correlationId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.CARD_COMMANDS,
                event.cardId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.CARD_COMMANDS)

            kafkaTemplate.send(record)
                .doOnSuccess { result: SenderResult<*> ->
                    log.info(
                        "Sent CardBlocked to topic={}, eventId={}, cardId={}",
                        KafkaTopic.CARD_COMMANDS, eventId, event.cardId
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error")
                    log.error("Failed to send CardBlocked to Kafka: {}", error.message, error)
                }
                .thenReturn(eventId)
        }
    }
}
