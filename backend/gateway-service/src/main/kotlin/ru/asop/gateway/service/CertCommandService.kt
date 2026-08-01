package ru.asop.gateway.service

import ru.asop.common.event.EventService

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.api.terminal.dto.request.CertSignRequest
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.terminal.CertSignRequested
import java.util.UUID

@Service
class CertCommandService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val eventService: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(request: CertSignRequest): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val command = CertSignRequested(
                terminalId = request.terminalId,
                terminalSerial = request.terminalSerial,
                terminalNumber = request.terminalNumber,
                terminalModel = request.terminalModel,
                carrierId = request.carrierId,
                publicKeyBase64 = request.publicKeyBase64,
                correlationId = eventId
            )
            eventId to command
        }.flatMap { (eventId, command) ->
            val record = ProducerRecord(
                KafkaTopic.TERMINAL_CERT_COMMANDS,
                request.terminalSerial,
                command as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.TERMINAL_CERT_COMMANDS)
                .then(kafkaTemplate.send(record))
                .doOnSuccess { result ->
                    log.info(
                        "CertSignRequested sent: eventId={}, terminalSerial={}, partition={}, offset={}",
                        eventId, request.terminalSerial,
                        result.recordMetadata().partition(), result.recordMetadata().offset()
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error").subscribe()
                    log.error(
                        "Failed to send CertSignRequested: eventId={}, error={}",
                        eventId, error.message, error
                    )
                }
                .thenReturn(eventId)
        }
    }
}
