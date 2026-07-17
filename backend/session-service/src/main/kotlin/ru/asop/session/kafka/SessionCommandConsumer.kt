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
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.session.SessionOpenedEvent
import ru.asop.kafka.events.session.SessionClosedEvent
import ru.asop.session.repository.SessionRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class SessionCommandConsumer(
    private val sessionRepository: SessionRepository,
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.session-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?
    ) {
        log.debug("Received session command: {}", json)
        val eventId = parseEventId(eventIdHeader, json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "SessionOpened" -> {
                    val event = objectMapper.treeToValue(node, SessionOpenedEvent::class.java)
                    handleSessionOpened(event, eventId)
                }
                "SessionClosed" -> {
                    val event = objectMapper.treeToValue(node, SessionClosedEvent::class.java)
                    handleSessionClosed(event, eventId)
                }
                else -> log.warn("Unknown session event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize session command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun handleSessionOpened(event: SessionOpenedEvent, eventId: UUID) {
        log.info("Processing SessionOpenedEvent: sessionId={}", event.sessionId)

        val sql = """INSERT INTO ASOP_SESSIONS 
            (SESSION_ID, SESSION_TYPE_ID, PARENT_SESSION_ID, TERMINAL_ID, PATH_ID, VEHICLE_ID, 
             STARTED_AT, CLOSED_AT, STARTED_AT_LOCAL, CLOSED_AT_LOCAL, EXPIRATION_TIME, STATUS) 
            VALUES (:sessionId, :sessionTypeId, :parentSessionId, :terminalId, :pathId, :vehicleId, 
                    :startedAt, NULL, :startedAtLocal, NULL, :expirationTime, :status)"""

        val startedAt = event.startedAt
        val expirationTime = startedAt.plus(Duration.ofHours(8))

        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
            .bind("sessionId", event.sessionId)
            .bind("sessionTypeId", event.sessionTypeId)
            .bind("startedAt", startedAt)
            .bind("startedAtLocal", startedAt)
            .bind("expirationTime", expirationTime)
            .bind("status", "IN_PROGRESS")

        val terminalId = event.terminalId
        val pathId = event.pathId
        val vehicleId = event.vehicleId

        spec = if (terminalId != null) spec.bind("terminalId", terminalId)
        else spec.bindNull("terminalId", UUID::class.java)
        spec = if (pathId != null) spec.bind("pathId", pathId)
        else spec.bindNull("pathId", UUID::class.java)
        spec = if (vehicleId != null) spec.bind("vehicleId", vehicleId)
        else spec.bindNull("vehicleId", UUID::class.java)
        spec = spec.bindNull("parentSessionId", UUID::class.java)

        spec.fetch().rowsUpdated()
            .doOnSuccess {
                log.info("Session saved: {}", event.sessionId)
                publishComplete(eventId, mapOf("sessionId" to event.sessionId.toString(), "status" to "IN_PROGRESS"))
            }
            .doOnError { e ->
                log.error("Failed to save session: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Save error")
            }
            .subscribe()
    }

    private fun handleSessionClosed(event: SessionClosedEvent, eventId: UUID) {
        log.info("Processing SessionClosedEvent: sessionId={}", event.sessionId)

        sessionRepository.findById(event.sessionId)
            .switchIfEmpty(Mono.error(IllegalStateException("Session not found: ${event.sessionId}")))
            .flatMap { existing ->
                val sql = """UPDATE ASOP_SESSIONS 
                    SET STATUS = 'CLOSED', CLOSED_AT = :closedAt, CLOSED_AT_LOCAL = :closedAt 
                    WHERE SESSION_ID = :sessionId"""
                db.sql(sql)
                    .bind("closedAt", event.closedAt)
                    .bind("sessionId", event.sessionId)
                    .fetch().rowsUpdated()
            }
            .doOnSuccess {
                log.info("Session closed: {}", event.sessionId)
                publishComplete(eventId, mapOf("sessionId" to event.sessionId.toString(), "status" to "CLOSED"))
            }
            .doOnError { e ->
                log.error("Failed to close session: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Close error")
            }
            .subscribe()
    }

    private fun publishComplete(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "COMPLETED", resultData = json)
        val record = ProducerRecord(KafkaTopic.SESSION_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        kafkaTemplate.send(record).subscribe()
    }

    private fun publishFailed(eventId: UUID, errorMessage: String) {
        val result = CommandResult(eventId = eventId, status = "FAILED", errorMessage = errorMessage)
        val record = ProducerRecord(KafkaTopic.SESSION_EVENTS, eventId.toString(), result as Any)
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
