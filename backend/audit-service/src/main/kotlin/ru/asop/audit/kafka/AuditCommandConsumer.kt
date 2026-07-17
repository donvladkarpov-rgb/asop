package ru.asop.audit.kafka

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
import ru.asop.kafka.events.audit.AuditTaskCreatedEvent
import java.time.Instant
import java.util.UUID

@Component
class AuditCommandConsumer(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.audit-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?
    ) {
        log.debug("Received audit command: {}", json)
        val eventId = parseEventId(eventIdHeader, json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "AuditTaskCreated" -> {
                    val event = objectMapper.treeToValue(node, AuditTaskCreatedEvent::class.java)
                    handleAuditTaskCreated(event, eventId)
                }
                else -> log.warn("Unknown audit event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize audit command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun handleAuditTaskCreated(event: AuditTaskCreatedEvent, eventId: UUID) {
        log.info("Processing AuditTaskCreatedEvent: taskId={}", event.taskId)

        val taskId = event.taskId ?: UUID.randomUUID()
        val issuerType = if (event.organizerId != null) "ORGANIZER" else "CARRIER"
        val now = Instant.now()

        val sql = """INSERT INTO ASOP_AUDIT_TASKS 
            (TASK_ID, TASK_NUMBER, ISSUER_TYPE, ORGANIZER_ID, CARRIER_ID, 
             ASSIGNED_AUDIT_SERVICE_ID, TASK_START_DATE, STATUS, DESCRIPTION, CREATED_AT, UPDATED_AT) 
            VALUES (:taskId, :taskNumber, :issuerType, :organizerId, :carrierId, 
                    (SELECT AUDIT_SERVICE_ID FROM ASOP_AUDIT_SERVICES 
                     WHERE (:issuerType = 'CARRIER' AND CARRIER_ID = :carrierId) 
                        OR (:issuerType = 'ORGANIZER' AND ORGANIZER_ID = :organizerId) 
                     LIMIT 1), 
                    :taskStartDate, 'DRAFT', :description, :createdAt, :updatedAt)"""

        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
            .bind("taskId", taskId)
            .bind("taskNumber", event.taskNumber)
            .bind("issuerType", issuerType)
            .bind("taskStartDate", now)
            .bind("createdAt", now)
            .bind("updatedAt", now)

        val description = event.description
        spec = if (description != null) spec.bind("description", description)
        else spec.bindNull("description", String::class.java)

        val organizerId = event.organizerId
        val carrierId = event.carrierId
        spec = if (organizerId != null) spec.bind("organizerId", organizerId)
        else spec.bindNull("organizerId", UUID::class.java)
        spec = if (carrierId != null) spec.bind("carrierId", carrierId)
        else spec.bindNull("carrierId", UUID::class.java)

        spec.fetch().rowsUpdated()
            .doOnSuccess {
                log.info("Audit task saved: {}", taskId)
                publishComplete(eventId, mapOf("taskId" to taskId.toString(), "status" to "DRAFT"))
            }
            .doOnError { e ->
                log.error("Failed to save audit task: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Save error")
            }
            .subscribe()
    }

    private fun publishComplete(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "COMPLETED", resultData = json)
        val record = ProducerRecord(KafkaTopic.AUDIT_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        kafkaTemplate.send(record).subscribe()
    }

    private fun publishFailed(eventId: UUID, errorMessage: String) {
        val result = CommandResult(eventId = eventId, status = "FAILED", errorMessage = errorMessage)
        val record = ProducerRecord(KafkaTopic.AUDIT_EVENTS, eventId.toString(), result as Any)
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
