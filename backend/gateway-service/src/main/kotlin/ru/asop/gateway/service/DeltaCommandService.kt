package ru.asop.gateway.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.api.gateway.dto.request.DeltaSyncRequest
import ru.asop.api.gateway.dto.request.FullSyncRequest
import ru.asop.common.event.EventService
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.delta.DeltaSyncCommand
import ru.asop.kafka.events.delta.FullSyncCommand
import java.util.UUID

@Service
class DeltaCommandService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val eventService: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publishDelta(request: DeltaSyncRequest, context: TerminalContext): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val command = DeltaSyncCommand(
                eventId = eventId,
                terminalId = request.terminalId,
                carrierId = context.carrierId,
                regionId = context.regionId,
                lastUpdatedAt = request.lastUpdatedAt
            )
            eventId to command
        }.flatMap { (eventId, command) ->
            val record = ProducerRecord(
                KafkaTopic.DELTA_COMMANDS,
                request.terminalId.toString(),
                command as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.DELTA_COMMANDS)
                .then(kafkaTemplate.send(record))
                .doOnSuccess { result ->
                    log.info(
                        "DeltaSyncCommand sent: eventId={}, terminalId={}, tables={}, partition={}, offset={}",
                        eventId, request.terminalId, request.lastUpdatedAt.size,
                        result.recordMetadata().partition(), result.recordMetadata().offset()
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error").subscribe()
                    log.error("Failed to send DeltaSyncCommand: eventId={}, error={}", eventId, error.message, error)
                }
                .thenReturn(eventId)
        }
    }

    fun publishFull(request: FullSyncRequest, context: TerminalContext): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val command = FullSyncCommand(
                eventId = eventId,
                terminalId = request.terminalId,
                carrierId = context.carrierId,
                regionId = context.regionId
            )
            eventId to command
        }.flatMap { (eventId, command) ->
            val record = ProducerRecord(
                KafkaTopic.DELTA_FULL_COMMANDS,
                request.terminalId.toString(),
                command as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.DELTA_FULL_COMMANDS)
                .then(kafkaTemplate.send(record))
                .doOnSuccess { result ->
                    log.info(
                        "FullSyncCommand sent: eventId={}, terminalId={}, partition={}, offset={}",
                        eventId, request.terminalId,
                        result.recordMetadata().partition(), result.recordMetadata().offset()
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error").subscribe()
                    log.error("Failed to send FullSyncCommand: eventId={}, error={}", eventId, error.message, error)
                }
                .thenReturn(eventId)
        }
    }
}
