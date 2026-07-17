package ru.asop.session.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import ru.asop.common.kafka.KafkaTopic
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.gps.GpsPositionReported
import java.util.UUID

@Component
class GpsCommandConsumer(
    private val db: DatabaseClient,
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
        val eventId = parseEventId(eventIdHeader, json)

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

        val positionId = event.positionId ?: UUID.randomUUID()
        val wkt = "POINT(${event.longitude} ${event.latitude})"

        val sql = """INSERT INTO ASOP_GPS_TRACKING 
            (POSITION_ID, VEHICLE_ID, PATH_ID, SESSION_ID, GPS_COORD, RECORDED_AT, SPEED_KMH, STATUS) 
            VALUES (:positionId, :vehicleId, :pathId, :sessionId, 
                    ST_GeogFromText(:wkt), :recordedAt, :speedKmh, :status)"""

        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
            .bind("positionId", positionId)
            .bind("vehicleId", event.vehicleId)
            .bind("pathId", event.pathId)
            .bind("wkt", wkt)
            .bind("recordedAt", event.recordedAt)
            .bind("status", "MOVING")

        val sessionId = event.sessionId
        val speedKmh = event.speedKmh

        spec = if (sessionId != null) spec.bind("sessionId", sessionId)
        else spec.bindNull("sessionId", UUID::class.java)
        spec = if (speedKmh != null) spec.bind("speedKmh", speedKmh)
        else spec.bindNull("speedKmh", java.math.BigDecimal::class.java)

        spec.fetch().rowsUpdated()
            .doOnSuccess {
                log.debug("GPS position saved: {}", positionId)
                publishComplete(eventId, mapOf("positionId" to positionId.toString()))
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

    private fun parseEventId(header: ByteArray?, json: String): UUID {
        if (header != null) {
            try { return UUID.fromString(String(header)) } catch (_: IllegalArgumentException) { }
        }
        val fromPayload = objectMapper.readTree(json).get("eventId")?.asText()
        if (fromPayload != null) {
            try { return UUID.fromString(fromPayload) } catch (_: IllegalArgumentException) { }
        }
        log.error("No valid eventId in header or payload, generating random (correlation will break)")
        return UUID.randomUUID()
    }
}
