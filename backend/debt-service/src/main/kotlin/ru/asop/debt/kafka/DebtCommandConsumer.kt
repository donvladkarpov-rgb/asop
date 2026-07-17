package ru.asop.debt.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import ru.asop.common.kafka.KafkaTopic
import ru.asop.debt.repository.DebtRepository
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.debt.DebtCreatedEvent
import ru.asop.kafka.events.debt.DebtRecoveredEvent
import java.time.Instant
import java.util.UUID

@Component
class DebtCommandConsumer(
    private val debtRepository: DebtRepository,
    private val db: DatabaseClient,
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
        val eventId = parseEventId(eventIdHeader, json)

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

        val sql = """INSERT INTO ASOP_CARD_DEBTS 
            (DEBT_ID, CARD_ID, SESSION_ID, TERMINAL_ID, CARRIER_ID, DEBT_AMOUNT, CURRENCY, 
             DEBT_STATUS, DEBT_OPENED_AT, DEBT_DUE_DATE, CREATED_AT, UPDATED_AT) 
            VALUES (:debtId, :cardId, :sessionId, :terminalId, :carrierId, :debtAmount, 'RUB', 
                    'OPEN', :debtOpenedAt, :debtDueDate, :createdAt, :updatedAt)"""

        val now = Instant.now()
        val sessionId = event.sessionId
        val terminalId = event.terminalId

        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
            .bind("debtId", event.debtId)
            .bind("cardId", event.cardId)
            .bind("carrierId", event.carrierId)
            .bind("debtAmount", event.debtAmount)
            .bind("debtOpenedAt", event.debtOpenedAt)
            .bind("debtDueDate", event.debtDueDate)
            .bind("createdAt", now)
            .bind("updatedAt", now)

        spec = if (sessionId != null) spec.bind("sessionId", sessionId)
        else spec.bindNull("sessionId", UUID::class.java)
        spec = if (terminalId != null) spec.bind("terminalId", terminalId)
        else spec.bindNull("terminalId", UUID::class.java)

        spec.fetch().rowsUpdated()
            .doOnSuccess {
                log.info("Debt saved: {}", event.debtId)
                publishComplete(eventId, mapOf("debtId" to event.debtId.toString(), "status" to "OPEN"))
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
            .switchIfEmpty(Mono.error(IllegalStateException("Debt not found: ${event.debtId}")))
            .flatMap { existing ->
                val sql = """UPDATE ASOP_CARD_DEBTS 
                    SET DEBT_STATUS = 'RECOVERED', RECOVERED_AT = :recoveredAt, UPDATED_AT = :updatedAt 
                    WHERE DEBT_ID = :debtId"""
                db.sql(sql)
                    .bind("recoveredAt", Instant.now())
                    .bind("updatedAt", Instant.now())
                    .bind("debtId", event.debtId)
                    .fetch().rowsUpdated()
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

    private fun parseEventId(header: ByteArray?, json: String): UUID {
        if (header != null) {
            try { return UUID.fromString(String(header)) } catch (_: IllegalArgumentException) { }
        }
        val fromPayload = objectMapper.readTree(json).get("eventId")?.asText()
        if (fromPayload != null) {
            try { return UUID.fromString(fromPayload) } catch (_: IllegalArgumentException) { }
        }
        log.error("No valid eventId in header or payload, generating random (correlation will break)")
        return UUID.randomUUID()
    }
}
