package ru.asop.session.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.watermark.WatermarkProcessor
import ru.asop.common.watermark.impl.WatermarkProcessorImpl
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
    private val watermark: WatermarkProcessor = WatermarkProcessorImpl(db, objectMapper)

    @KafkaListener(topics = ["\${asop.kafka.topics.gps-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?,
        @Header(name = "X-Terminal-Seq", required = false) terminalSeqHeader: ByteArray?
    ) {
        log.debug("Received GPS command: {}", json.take(200))
        val eventId = parseEventId(eventIdHeader, json)
        val seq = parseSeq(terminalSeqHeader)
        val terminalId = parseTerminalId(json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "GpsPositionReported" -> {
                    val event = objectMapper.treeToValue(node, GpsPositionReported::class.java)
                    handleGpsPositionReported(event, eventId, terminalId, seq, json)
                }
                else -> log.warn("Unknown GPS event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize GPS command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun handleGpsPositionReported(event: GpsPositionReported, eventId: UUID, terminalId: UUID?, seq: Long, rawJson: String) {
        log.debug("Processing GpsPositionReported: positionId={} seq={}", event.positionId, seq)

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

        val applyMono: Mono<Void> = spec.fetch().rowsUpdated().then()

        if (terminalId == null) {
            applyMono
                .doOnSuccess { publishComplete(eventId, mapOf("positionId" to positionId.toString())) }
                .doOnError { e -> publishFailed(eventId, e.message ?: "Save error") }
                .subscribe()
        } else {
            val tid = terminalId
            watermark.applyInOrder(tid, seq, "GpsPositionReported", rawJson, emptyMap(), applyMono)
                .doOnSuccess { result ->
                    when (result) {
                        WatermarkProcessor.ApplyResult.APPLIED, WatermarkProcessor.ApplyResult.ALREADY_APPLIED -> {
                            log.debug("GPS position saved [seq={}]: {}", seq, positionId)
                            publishComplete(eventId, mapOf("positionId" to positionId.toString()))
                        }
                        WatermarkProcessor.ApplyResult.DEFERRED -> {
                            log.debug("GPS position deferred [seq={}]: {}", seq, positionId)
                            publishPending(eventId, mapOf("positionId" to positionId.toString(), "seq" to seq.toString()))
                        }
                    }
                }
                .doOnError { e -> publishFailed(eventId, e.message ?: "Save error") }
                .subscribe()
        }
    }

    private fun publishComplete(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "COMPLETED", resultData = json)
        kafkaTemplate.send(buildResultRecord(eventId, result)).subscribe()
    }

    private fun publishPending(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "PENDING_WATERMARK", resultData = json)
        kafkaTemplate.send(buildResultRecord(eventId, result)).subscribe()
    }

    private fun publishFailed(eventId: UUID, errorMessage: String) {
        val result = CommandResult(eventId = eventId, status = "FAILED", errorMessage = errorMessage)
        kafkaTemplate.send(buildResultRecord(eventId, result)).subscribe()
    }

    private fun buildResultRecord(eventId: UUID, result: CommandResult): ProducerRecord<String, Any> {
        val record = ProducerRecord(KafkaTopic.GPS_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        return record
    }

    private fun parseEventId(header: ByteArray?, json: String): UUID {
        if (header != null) {
            try { return UUID.fromString(String(header)) } catch (_: IllegalArgumentException) { }
        }
        val fromPayload = objectMapper.readTree(json).get("eventId")?.asText()
        if (fromPayload != null) {
            try { return UUID.fromString(fromPayload) } catch (_: IllegalArgumentException) { }
        }
        log.error("No valid eventId in header or payload, generating random")
        return UUID.randomUUID()
    }

    private fun parseSeq(header: ByteArray?): Long {
        if (header == null) return 0L
        return try { String(header).toLong() } catch (_: NumberFormatException) { 0L }
    }

    private fun parseTerminalId(json: String): UUID? = try {
        val node = objectMapper.readTree(json)
        val s = node.get("terminalId")?.asText() ?: return null
        UUID.fromString(s)
    } catch (_: Exception) { null }
}
