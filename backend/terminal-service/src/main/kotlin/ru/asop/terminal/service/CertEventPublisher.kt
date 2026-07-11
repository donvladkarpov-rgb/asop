package ru.asop.terminal.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.common.kafka.KafkaTopic
import ru.asop.kafka.events.terminal.CertSignFailed
import ru.asop.kafka.events.terminal.CertStored
import java.util.UUID

@Service
class CertEventPublisher(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publishStored(event: CertStored): Mono<Void> {
        return Mono.fromRunnable<Unit> {
            val record = ProducerRecord(
                KafkaTopic.TERMINAL_CERT_EVENTS,
                event.terminalId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", event.correlationId.toString().encodeToByteArray())

            kafkaTemplate.send(record)
                .doOnSuccess { result ->
                    log.info(
                        "CertStored published: eventId={}, terminalId={}, certId={}, partition={}, offset={}",
                        event.correlationId, event.terminalId, event.certId,
                        result.recordMetadata().partition(), result.recordMetadata().offset()
                    )
                }
                .doOnError { error ->
                    log.error(
                        "Failed to publish CertStored: eventId={}, error={}",
                        event.correlationId, error.message, error
                    )
                }
                .subscribe()
        }.then()
    }

    fun publishFailed(eventId: UUID, terminalSerial: String?, terminalId: UUID?, reason: String): Mono<Void> {
        return Mono.fromRunnable<Unit> {
            val event = CertSignFailed(
                terminalId = terminalId,
                terminalSerial = terminalSerial,
                reason = reason,
                correlationId = eventId
            )
            val record = ProducerRecord(
                KafkaTopic.TERMINAL_CERT_EVENTS,
                terminalSerial ?: eventId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            kafkaTemplate.send(record)
                .doOnSuccess { _ ->
                    log.warn(
                        "CertSignFailed published: eventId={}, terminalSerial={}, reason={}",
                        eventId, terminalSerial, reason
                    )
                }
                .doOnError { error ->
                    log.error(
                        "Failed to publish CertSignFailed: eventId={}, error={}",
                        eventId, error.message, error
                    )
                }
                .subscribe()
        }.then()
    }
}