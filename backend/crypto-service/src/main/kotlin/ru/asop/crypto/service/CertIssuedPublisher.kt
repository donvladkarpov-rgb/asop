package ru.asop.crypto.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.common.kafka.KafkaTopic
import ru.asop.kafka.events.terminal.CertIssued
import ru.asop.kafka.events.terminal.CertSignFailed
import java.time.Instant
import java.util.UUID

@Service
class CertIssuedPublisher(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publishIssued(
        eventId: UUID,
        terminalId: UUID,
        terminalSerial: String,
        terminalNumber: String?,
        certificateBase64: String,
        serialNumber: String,
        validFrom: Instant,
        validUntil: Instant,
        caChain: String
    ): Mono<Void> {
        return Mono.fromRunnable<Unit> {
            val event = CertIssued(
                terminalId = terminalId,
                terminalSerial = terminalSerial,
                terminalNumber = terminalNumber,
                certificateBase64 = certificateBase64,
                certSerialNumber = serialNumber,
                validFrom = validFrom,
                validUntil = validUntil,
                caChain = caChain,
                correlationId = eventId
            )
            val record = ProducerRecord(
                KafkaTopic.TERMINAL_CERT_ISSUED,
                terminalSerial,
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            kafkaTemplate.send(record)
                .doOnSuccess { result ->
                    log.info(
                        "CertIssued published: eventId={}, terminalSerial={}, certSerial={}, partition={}, offset={}",
                        eventId, terminalSerial, serialNumber,
                        result.recordMetadata().partition(), result.recordMetadata().offset()
                    )
                }
                .doOnError { error ->
                    log.error(
                        "Failed to publish CertIssued: eventId={}, error={}",
                        eventId, error.message, error
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
                KafkaTopic.TERMINAL_CERT_ISSUED,
                terminalSerial ?: eventId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            kafkaTemplate.send(record)
                .doOnSuccess { _ ->
                    log.warn(
                        "CertSignFailed published (crypto): eventId={}, terminalSerial={}, reason={}",
                        eventId, terminalSerial, reason
                    )
                }
                .doOnError { error ->
                    log.error(
                        "Failed to publish CertSignFailed (crypto): eventId={}, error={}",
                        eventId, error.message, error
                    )
                }
                .subscribe()
        }.then()
    }
}