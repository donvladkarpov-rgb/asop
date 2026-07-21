package ru.asop.carrier.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.common.kafka.KafkaTopic
import java.util.UUID

@Service
class CarrierEventPublisher(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publishSuccess(eventId: UUID, carrierId: UUID): Mono<Void> {
        return Mono.fromRunnable<Unit> {
            val payload = mapOf(
                "eventType" to "CarrierStored",
                "aggregateId" to carrierId.toString()
            )
            val record = ProducerRecord(
                KafkaTopic.CARRIER_EVENTS,
                carrierId.toString(),
                payload as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            kafkaTemplate.send(record)
                .doOnSuccess { result ->
                    log.info(
                        "CarrierStored published: eventId={}, carrierId={}, partition={}, offset={}",
                        eventId, carrierId,
                        result.recordMetadata().partition(), result.recordMetadata().offset()
                    )
                }
                .doOnError { error ->
                    log.error("Failed to publish CarrierStored: eventId={}, error={}", eventId, error.message, error)
                }
                .subscribe()
        }.then()
    }

    fun publishFailed(eventId: UUID, carrierId: UUID, reason: String): Mono<Void> {
        return Mono.fromRunnable<Unit> {
            val payload = mapOf(
                "eventType" to "CarrierStoreFailed",
                "aggregateId" to carrierId.toString(),
                "errorMessage" to reason
            )
            val record = ProducerRecord(
                KafkaTopic.CARRIER_EVENTS,
                carrierId.toString(),
                payload as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            kafkaTemplate.send(record)
                .doOnSuccess { result ->
                    log.warn(
                        "CarrierStoreFailed published: eventId={}, carrierId={}, reason={}",
                        eventId, carrierId, reason
                    )
                }
                .doOnError { error ->
                    log.error("Failed to publish CarrierStoreFailed: eventId={}, error={}", eventId, error.message, error)
                }
                .subscribe()
        }.then()
    }
}
