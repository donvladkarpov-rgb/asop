package ru.asop.terminal.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.asop.terminal.model.TerminalEntity
import ru.asop.terminal.repository.TerminalRepository
import ru.asop.kafka.events.terminal.TerminalRegisteredEvent
import ru.asop.kafka.events.terminal.TerminalStatusChangedEvent

@Component
class TerminalCommandConsumer(
    private val terminalRepository: TerminalRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.terminal-commands}"])
    fun handleCommand(json: String) {
        log.debug("Received terminal command: {}", json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "TerminalRegistered" -> {
                    val event = objectMapper.treeToValue(node, TerminalRegisteredEvent::class.java)
                    handleTerminalRegistered(event)
                }
                "TerminalStatusChanged" -> {
                    val event = objectMapper.treeToValue(node, TerminalStatusChangedEvent::class.java)
                    handleTerminalStatusChanged(event)
                }
                else -> log.warn("Unknown terminal event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize terminal command: {}", e.message, e)
        }
    }

    private fun handleTerminalRegistered(event: TerminalRegisteredEvent) {
        log.info("Processing TerminalRegisteredEvent: terminalId={}", event.terminalId)

        val entity = TerminalEntity(
            terminalId = event.terminalId,
            terminalSerial = event.terminalSerial,
            terminalNumber = event.terminalNumber,
            terminalModel = event.terminalModel,
            carrierId = event.carrierId,
            status = event.status,
            createdAt = event.registeredAt,
            updatedAt = event.registeredAt
        )
        terminalRepository.save(entity)
            .doOnSuccess { log.info("Terminal saved: {}", it.terminalId) }
            .doOnError { e -> log.error("Failed to save terminal: {}", e.message, e) }
            .subscribe()
    }

    private fun handleTerminalStatusChanged(event: TerminalStatusChangedEvent) {
        log.info("Processing TerminalStatusChangedEvent: terminalId={}, newStatus={}",
            event.terminalId, event.newStatus)

        terminalRepository.findById(event.terminalId)
            .flatMap { existing ->
                val updated = existing.copy(
                    status = event.newStatus,
                    updatedAt = event.changedAt
                )
                terminalRepository.save(updated)
            }
            .doOnSuccess { log.info("Terminal status updated: {}", event.terminalId) }
            .doOnError { e -> log.error("Failed to update terminal: {}", e.message, e) }
            .subscribe()
    }
}
