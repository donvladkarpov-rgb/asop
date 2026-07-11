package ru.asop.gateway.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.asop.gateway.service.EventService
import java.util.UUID

/**
 * Слушает результаты cert-операций от terminal-service (asop.terminal.cert.events).
 * Обновляет EventService: PENDING → COMPLETED (+ resultData JSON) или PENDING → FAILED.
 * X-Event-Id пробрасывается из terminal-service обратно в Gateway для корреляции.
 */
@Component
class CertEventConsumer(
    private val eventService: EventService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${asop.kafka.topics.terminal-cert-events}"],
        groupId = "gateway-cert-events"
    )
    fun onCertEvent(record: ConsumerRecord<String, String>) {
        val eventId = extractEventId(record)
        if (eventId == null) {
            log.warn("Cert event without X-Event-Id header, skipping: key={}", record.key())
            return
        }

        try {
            val node = objectMapper.readTree(record.value())
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "CertStored" -> {
                    val resultData = objectMapper.writeValueAsString(
                        mapOf(
                            "certId" to node.get("certId").asText(),
                            "terminalId" to node.get("terminalId").asText(),
                            "terminalNumber" to (node.get("terminalNumber")?.asText() ?: ""),
                            "certSerial" to node.get("certSerial").asText(),
                            "certificateBase64" to node.get("certificateBase64").asText(),
                            "validFrom" to node.get("validFrom").asText(),
                            "validUntil" to node.get("validUntil").asText(),
                            "caChain" to node.get("caChain").asText()
                        )
                    )
                    eventService.complete(eventId, resultData)
                    log.info("CertStored → EventService COMPLETED: eventId={}", eventId)
                }
                "CertSignFailed" -> {
                    val reason = node.get("reason")?.asText() ?: "Unknown error"
                    eventService.fail(eventId, reason)
                    log.warn("CertSignFailed → EventService FAILED: eventId={}, reason={}", eventId, reason)
                }
                else -> {
                    log.warn("Unknown cert event type '{}', skipping: eventId={}", eventType, eventId)
                }
            }
        } catch (e: Exception) {
            log.error("Failed to process cert event: eventId={}, error={}", eventId, e.message, e)
            eventService.fail(eventId, "Failed to process cert event: ${e.message}")
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