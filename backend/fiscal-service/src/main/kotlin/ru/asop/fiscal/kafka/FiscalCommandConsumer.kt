package ru.asop.fiscal.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import ru.asop.common.kafka.KafkaTopic
import ru.asop.fiscal.service.FiscalReceiptService
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.fiscal.FiscalReceiptRequestedEvent
import java.util.UUID

@Component
class FiscalCommandConsumer(
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val service: FiscalReceiptService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.fiscal-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?
    ) {
        log.debug("Received fiscal command: {}", json.take(200))
        val eventId = parseEventId(eventIdHeader, json)
        try {
            val node = objectMapper.readTree(json)
            when (node.get("eventType")?.asText()) {
                "FiscalReceiptRequested" -> {
                    val event = objectMapper.treeToValue(node, FiscalReceiptRequestedEvent::class.java)
                    service.handleRequested(event)
                        .doOnSuccess { publishComplete(eventId, mapOf("receiptId" to event.receiptId.toString())) }
                        .doOnError { e -> publishFailed(eventId, e.message ?: "error") }
                        .subscribe()
                }
                else -> log.warn("Unknown fiscal event type: {}", node.get("eventType")?.asText())
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize fiscal command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun publishComplete(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "COMPLETED", resultData = json)
        kafkaTemplate.send(buildResultRecord(eventId, result)).subscribe()
    }

    private fun publishFailed(eventId: UUID, errorMessage: String) {
        val result = CommandResult(eventId = eventId, status = "FAILED", errorMessage = errorMessage)
        kafkaTemplate.send(buildResultRecord(eventId, result)).subscribe()
    }

    private fun buildResultRecord(eventId: UUID, result: CommandResult): ProducerRecord<String, Any> {
        val record = ProducerRecord(KafkaTopic.FISCAL_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        return record
    }

    private fun parseEventId(header: ByteArray?, json: String): UUID {
        if (header != null) {
            try { return UUID.fromString(String(header)) } catch (_: IllegalArgumentException) { }
        }
        return try {
            val fromPayload = objectMapper.readTree(json).get("eventId")?.asText()
            if (fromPayload != null) UUID.fromString(fromPayload) else UUID.randomUUID()
        } catch (_: Exception) {
            UUID.randomUUID()
        }
    }
}
