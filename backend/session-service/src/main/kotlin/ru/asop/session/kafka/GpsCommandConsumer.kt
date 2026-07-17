package ru.asop.session.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import ru.asop.common.kafka.KafkaTopic
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.gps.GpsPositionReported
import ru.asop.session.model.GpsTrackingEntity
import ru.asop.session.repository.GpsTrackingRepository
import java.util.UUID

@Component
class GpsCommandConsumer(
    private val gpsTrackingRepository: GpsTrackingRepository,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.gps-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?
    ) {
        log.debug("Received GPS command: {}", json)
        val eventId = parseEventId(eventIdHeader)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "GpsPositionReported" -> {
                    val event = objectMapper.treeToValue(node, GpsPositionReported::class.java)
                    handleGpsPositionReported(event, eventId)
                }
                else -> log.warn("Unknown GPS event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize GPS command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun handleGpsPositionReported(event: GpsPositionReported, eventId: UUID) {
        log.debug("Processing GpsPositionReported: positionId={}", event.positionId)

        val entity = GpsTrackingEntity(
            positionId = event.positionId ?: UUID.randomUUID(),
            vehicleId = event.vehicleId,
            pathId = event.pathId,
            sessionId = event.sessionId,
            gpsCoord = "POINT(${event.longitude} ${event.latitude})",
            recordedAt = event.recordedAt,
            speedKmh = event.speedKmh
        )
        gpsTrackingRepository.save(entity)
            .doOnSuccess {
                log.debug("GPS position saved: {}", it.positionId)
                publishComplete(eventId, mapOf("positionId" to it.positionId.toString()))
            }
            .doOnError { e ->
                log.error("Failed to save GPS position: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Save error")
            }
            .subscribe()
    }

    private fun publishComplete(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "COMPLETED", resultData = json)
        val record = ProducerRecord(KafkaTopic.GPS_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        kafkaTemplate.send(record).subscribe()
    }

    private fun publishFailed(eventId: UUID, errorMessage: String) {
        val result = CommandResult(eventId = eventId, status = "FAILED", errorMessage = errorMessage)
        val record = ProducerRecord(KafkaTopic.GPS_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        kafkaTemplate.send(record).subscribe()
    }

    private fun parseEventId(header: ByteArray?): UUID {
        if (header == null) return UUID.randomUUID()
        return try {
            UUID.fromString(String(header))
        } catch (e: IllegalArgumentException) {
            UUID.randomUUID()
        }
    }
}
