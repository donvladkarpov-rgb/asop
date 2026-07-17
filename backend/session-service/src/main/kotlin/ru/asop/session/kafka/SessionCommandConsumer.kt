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
import ru.asop.kafka.events.session.SessionOpenedEvent
import ru.asop.kafka.events.session.SessionClosedEvent
import ru.asop.session.model.SessionEntity
import ru.asop.session.repository.SessionRepository
import java.util.UUID

@Component
class SessionCommandConsumer(
    private val sessionRepository: SessionRepository,
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
        val eventId = parseEventId(eventIdHeader)

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

        val entity = SessionEntity(
            sessionId = event.sessionId,
            sessionTypeId = event.sessionTypeId,
            parentSessionId = null,
            terminalId = event.terminalId,
            pathId = event.pathId,
            vehicleId = event.vehicleId,
            status = "IN_PROGRESS",
            startedAt = event.startedAt,
            createdAt = event.occurredAt,
            updatedAt = event.occurredAt
        )
        sessionRepository.save(entity)
            .doOnSuccess {
                log.info("Session saved: {}", it.sessionId)
                publishComplete(eventId, mapOf("sessionId" to it.sessionId.toString(), "status" to "IN_PROGRESS"))
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
            .flatMap { existing ->
                val updated = existing.copy(
                    status = "CLOSED",
                    closedAt = event.closedAt,
                    updatedAt = event.closedAt
                )
                sessionRepository.save(updated)
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

    private fun parseEventId(header: ByteArray?): UUID {
        if (header == null) return UUID.randomUUID()
        return try {
            UUID.fromString(String(header))
        } catch (e: IllegalArgumentException) {
            UUID.randomUUID()
        }
    }
}
