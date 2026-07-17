package ru.asop.debt.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import ru.asop.common.kafka.KafkaTopic
import ru.asop.debt.model.DebtEntity
import ru.asop.debt.repository.DebtRepository
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.debt.DebtCreatedEvent
import ru.asop.kafka.events.debt.DebtRecoveredEvent
import java.time.Instant
import java.util.UUID

@Component
class DebtCommandConsumer(
    private val debtRepository: DebtRepository,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.debt-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?
    ) {
        log.debug("Received debt command: {}", json)
        val eventId = parseEventId(eventIdHeader)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "DebtCreated" -> {
                    val event = objectMapper.treeToValue(node, DebtCreatedEvent::class.java)
                    handleDebtCreated(event, eventId)
                }
                "DebtRecovered" -> {
                    val event = objectMapper.treeToValue(node, DebtRecoveredEvent::class.java)
                    handleDebtRecovered(event, eventId)
                }
                else -> log.warn("Unknown debt event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize debt command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun handleDebtCreated(event: DebtCreatedEvent, eventId: UUID) {
        log.info("Processing DebtCreatedEvent: debtId={}", event.debtId)

        val entity = DebtEntity(
            debtId = event.debtId,
            cardId = event.cardId,
            carrierId = event.carrierId,
            debtAmount = event.debtAmount,
            debtStatus = "OPEN",
            debtOpenedAt = event.debtOpenedAt,
            debtDueDate = event.debtDueDate,
            terminalId = event.terminalId,
            sessionId = event.sessionId,
            createdAt = event.occurredAt,
            updatedAt = event.occurredAt
        )
        debtRepository.save(entity)
            .doOnSuccess {
                log.info("Debt saved: {}", it.debtId)
                publishComplete(eventId, mapOf("debtId" to it.debtId.toString(), "status" to "OPEN"))
            }
            .doOnError { e ->
                log.error("Failed to save debt: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Save error")
            }
            .subscribe()
    }

    private fun handleDebtRecovered(event: DebtRecoveredEvent, eventId: UUID) {
        log.info("Processing DebtRecoveredEvent: debtId={}", event.debtId)

        debtRepository.findById(event.debtId)
            .flatMap { existing ->
                val updated = existing.copy(
                    debtStatus = "RECOVERED",
                    updatedAt = Instant.now()
                )
                debtRepository.save(updated)
            }
            .doOnSuccess {
                log.info("Debt recovered: {}", event.debtId)
                publishComplete(eventId, mapOf("debtId" to event.debtId.toString(), "status" to "RECOVERED"))
            }
            .doOnError { e ->
                log.error("Failed to recover debt: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Recover error")
            }
            .subscribe()
    }

    private fun publishComplete(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "COMPLETED", resultData = json)
        val record = ProducerRecord(KafkaTopic.DEBT_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        kafkaTemplate.send(record).subscribe()
    }

    private fun publishFailed(eventId: UUID, errorMessage: String) {
        val result = CommandResult(eventId = eventId, status = "FAILED", errorMessage = errorMessage)
        val record = ProducerRecord(KafkaTopic.DEBT_EVENTS, eventId.toString(), result as Any)
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
