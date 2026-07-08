package ru.asop.debt.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.asop.debt.repository.DebtRepository
import ru.asop.kafka.events.debt.DebtCreatedEvent
import ru.asop.kafka.events.debt.DebtRecoveredEvent

@Component
class DebtCommandConsumer(
    private val debtRepository: DebtRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.debt-commands}"])
    fun handleCommand(json: String) {
        log.debug("Received debt command: {}", json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "DebtCreated" -> {
                    val event = objectMapper.treeToValue(node, DebtCreatedEvent::class.java)
                    handleDebtCreated(event)
                }
                "DebtRecovered" -> {
                    val event = objectMapper.treeToValue(node, DebtRecoveredEvent::class.java)
                    handleDebtRecovered(event)
                }
                else -> log.warn("Unknown debt event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize debt command: {}", e.message, e)
        }
    }

    private fun handleDebtCreated(event: DebtCreatedEvent) {
        log.info("Processing DebtCreatedEvent: debtId={}", event.debtId)
    }

    private fun handleDebtRecovered(event: DebtRecoveredEvent) {
        log.info("Processing DebtRecoveredEvent: debtId={}", event.debtId)
    }
}
