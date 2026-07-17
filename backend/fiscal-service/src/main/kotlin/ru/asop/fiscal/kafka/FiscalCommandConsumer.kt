package ru.asop.fiscal.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import ru.asop.common.kafka.KafkaTopic
import ru.asop.fiscal.model.FiscalReceiptEntity
import ru.asop.fiscal.repository.FiscalReceiptRepository
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.fiscal.FiscalReceiptRequestedEvent
import java.util.UUID

@Component
class FiscalCommandConsumer(
    private val fiscalReceiptRepository: FiscalReceiptRepository,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.fiscal-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?
    ) {
        log.debug("Received fiscal command: {}", json)
        val eventId = parseEventId(eventIdHeader)

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

        val entity = FiscalReceiptEntity(
            receiptId = event.receiptId,
            transactionId = event.transactionId,
            amount = event.amount,
            status = "PENDING",
            createdAt = event.occurredAt,
            updatedAt = event.occurredAt
        )
        fiscalReceiptRepository.save(entity)
            .doOnSuccess {
                log.info("Fiscal receipt saved: {}", it.receiptId)
                publishComplete(eventId, mapOf("receiptId" to it.receiptId.toString(), "status" to "PENDING"))
            }
            .doOnError { e ->
                log.error("Failed to save fiscal receipt: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Save error")
            }
            .subscribe()
    }

    private fun publishComplete(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "COMPLETED", resultData = json)
        val record = ProducerRecord(KafkaTopic.FISCAL_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        kafkaTemplate.send(record).subscribe()
    }

    private fun publishFailed(eventId: UUID, errorMessage: String) {
        val result = CommandResult(eventId = eventId, status = "FAILED", errorMessage = errorMessage)
        val record = ProducerRecord(KafkaTopic.FISCAL_EVENTS, eventId.toString(), result as Any)
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
