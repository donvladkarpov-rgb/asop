package ru.asop.gateway.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.asop.gateway.service.EventService
import java.util.UUID

@Component
class CommandEventConsumer(
    private val eventService: EventService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [
            "\${asop.kafka.topics.carrier-events}",
            "\${asop.kafka.topics.session-events}",
            "\${asop.kafka.topics.transaction-events}",
            "\${asop.kafka.topics.card-events}",
            "\${asop.kafka.topics.debt-events}",
            "\${asop.kafka.topics.fiscal-events}",
            "\${asop.kafka.topics.audit-events}",
            "\${asop.kafka.topics.gps-events}"
        ],
        groupId = "gateway-command-events"
    )
    fun onCommandEvent(record: ConsumerRecord<String, String>) {
        val eventId = extractEventId(record)
        if (eventId == null) {
            log.debug("Command event without X-Event-Id header, skipping: key={}, topic={}",
                record.key(), record.topic())
            return
        }

        try {
            val node = objectMapper.readTree(record.value())
            val eventType = node.get("eventType")?.asText() ?: "Unknown"
            val aggregateId = node.get("aggregateId")?.asText()
            val errorMessage = node.get("errorMessage")?.asText()

            val resultData = buildString {
                append("{\"eventType\":\"")
                append(eventType)
                append("\",\"topic\":\"")
                append(record.topic())
                if (aggregateId != null) {
                    append("\",\"entityId\":\"")
                    append(aggregateId)
                }
                append("\"}")
            }

            if (errorMessage != null) {
                eventService.fail(eventId, errorMessage).subscribe()
                log.warn("CommandEvent {} -> EventService FAILED: eventId={}, topic={}, error={}",
                    eventType, eventId, record.topic(), errorMessage)
            } else {
                eventService.complete(eventId, resultData).subscribe()
                log.debug("CommandEvent {} -> EventService COMPLETED: eventId={}, topic={}",
                    eventType, eventId, record.topic())
            }
        } catch (e: Exception) {
            log.error("Failed to process command event: eventId={}, topic={}, error={}",
                eventId, record.topic(), e.message, e)
            eventService.fail(eventId, "Failed to process event: ${e.message}").subscribe()
        }
    }

    private fun extractEventId(record: ConsumerRecord<String, String>): UUID? {
        val header = record.headers().lastHeader("X-Event-Id") ?: return null
        return try {
            UUID.fromString(String(header.value()))
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid X-Event-Id header value: {}", String(header.value()))
            null
        }
    }
}
