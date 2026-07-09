package ru.asop.session.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.asop.session.model.SessionEntity
import ru.asop.session.repository.SessionRepository
import ru.asop.kafka.events.session.SessionOpenedEvent
import ru.asop.kafka.events.session.SessionClosedEvent

@Component
class SessionCommandConsumer(
    private val sessionRepository: SessionRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.session-commands}"])
    fun handleCommand(json: String) {
        log.debug("Received session command: {}", json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "SessionOpened" -> {
                    val event = objectMapper.treeToValue(node, SessionOpenedEvent::class.java)
                    handleSessionOpened(event)
                }
                "SessionClosed" -> {
                    val event = objectMapper.treeToValue(node, SessionClosedEvent::class.java)
                    handleSessionClosed(event)
                }
                else -> log.warn("Unknown session event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize session command: {}", e.message, e)
        }
    }

    private fun handleSessionOpened(event: SessionOpenedEvent) {
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
            .doOnSuccess { log.info("Session saved: {}", it.sessionId) }
            .doOnError { e -> log.error("Failed to save session: {}", e.message, e) }
            .subscribe()
    }

    private fun handleSessionClosed(event: SessionClosedEvent) {
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
            .doOnSuccess { log.info("Session closed: {}", event.sessionId) }
            .doOnError { e -> log.error("Failed to close session: {}", e.message, e) }
            .subscribe()
    }
}
