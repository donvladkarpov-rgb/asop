package ru.asop.fiscal.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import ru.asop.common.kafka.KafkaTopic
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.fiscal.FiscalReceiptRequestedEvent
import java.util.UUID

@Component
class FiscalCommandConsumer(
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.fiscal-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?,
        @Header(name = "X-Terminal-Seq", required = false) terminalSeqHeader: ByteArray?
    ) {
        log.debug("Received fiscal command: {}", json.take(200))
        val eventId = parseEventId(eventIdHeader, json)
        val seq = parseSeq(terminalSeqHeader)
        log.debug("Fiscal receipt event seq={}", seq)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "FiscalReceiptRequested" -> {
                    val event = objectMapper.treeToValue(node, FiscalReceiptRequestedEvent::class.java)
                    handleFiscalReceiptRequested(event, eventId)
                }
                else -> log.warn("Unknown fiscal event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize fiscal command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun handleFiscalReceiptRequested(event: FiscalReceiptRequestedEvent, eventId: UUID) {
        log.info("Processing FiscalReceiptRequestedEvent: receiptId={}", event.receiptId)
        publishFailed(
            eventId,
            "Fiscal receipt creation not implemented: ASOP_FISCAL_RECEIPTS requires CARRIER_ID and " +
                "CARRIER_FISCALIZER_ID (NOT NULL). Need carrier resolution from transaction → carrier → fiscalizer. " +
                "Receipt ID: ${event.receiptId}, Transaction ID: ${event.transactionId}"
        )
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
        val record = ProducerRecord(KafkaTopic.FISCAL_EVENTS, eventId.toString(), result as Any)
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
}
