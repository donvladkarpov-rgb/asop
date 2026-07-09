package ru.asop.fiscal.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.asop.fiscal.repository.FiscalReceiptRepository
import ru.asop.kafka.events.fiscal.FiscalReceiptRequestedEvent
import ru.asop.kafka.events.fiscal.FiscalReceiptConfirmedEvent

@Component
class FiscalCommandConsumer(
    private val fiscalReceiptRepository: FiscalReceiptRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.fiscal-commands}"])
    fun handleCommand(json: String) {
        log.debug("Received fiscal command: {}", json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "FiscalReceiptRequested" -> {
                    val event = objectMapper.treeToValue(node, FiscalReceiptRequestedEvent::class.java)
                    handleReceiptRequested(event)
                }
                "FiscalReceiptConfirmed" -> {
                    val event = objectMapper.treeToValue(node, FiscalReceiptConfirmedEvent::class.java)
                    handleReceiptConfirmed(event)
                }
                else -> log.warn("Unknown fiscal event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize fiscal command: {}", e.message, e)
        }
    }

    private fun handleReceiptRequested(event: FiscalReceiptRequestedEvent) {
        log.info("Processing FiscalReceiptRequestedEvent: receiptId={}", event.receiptId)
    }

    private fun handleReceiptConfirmed(event: FiscalReceiptConfirmedEvent) {
        log.info("Processing FiscalReceiptConfirmedEvent: receiptId={}", event.receiptId)
    }
}
