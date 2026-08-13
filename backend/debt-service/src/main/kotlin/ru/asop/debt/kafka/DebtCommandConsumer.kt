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
import ru.asop.common.watermark.WatermarkProcessor
import ru.asop.common.watermark.impl.WatermarkProcessorImpl
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
    private val watermark: WatermarkProcessor = WatermarkProcessorImpl(db, objectMapper)

    @KafkaListener(topics = ["\${asop.kafka.topics.debt-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?,
        @Header(name = "X-Terminal-Seq", required = false) terminalSeqHeader: ByteArray?
    ) {
        log.debug("Received debt command: {}", json.take(200))
        val eventId = parseEventId(eventIdHeader, json)
        val seq = parseSeq(terminalSeqHeader)
        val terminalId = parseTerminalId(json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "DebtCreated" -> {
                    val event = objectMapper.treeToValue(node, DebtCreatedEvent::class.java)
                    handleDebtCreated(event, eventId, terminalId, seq, json)
                }
                "DebtRecovered" -> {
                    val event = objectMapper.treeToValue(node, DebtRecoveredEvent::class.java)
                    handleDebtRecovered(event, eventId, terminalId, seq, json)
                }
                else -> log.warn("Unknown debt event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize debt command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun handleDebtCreated(event: DebtCreatedEvent, eventId: UUID, terminalId: UUID?, seq: Long, rawJson: String) {
        log.info("Processing DebtCreatedEvent: debtId={} seq={}", event.debtId, seq)

        val sql = """INSERT INTO ASOP_CARD_DEBTS
            (DEBT_ID, CARD_ID, SESSION_ID, TERMINAL_ID, CARRIER_ID, DEBT_AMOUNT, CURRENCY,
             DEBT_STATUS, DEBT_OPENED_AT, DEBT_DUE_DATE, CREATED_AT, UPDATED_AT)
            VALUES (:debtId, :cardId, :sessionId, :terminalId, :carrierId, :debtAmount, 'RUB',
                    'OPEN', :debtOpenedAt, :debtDueDate, :createdAt, :updatedAt)"""

        val now = Instant.now()
        val sessionId = event.sessionId
        val eventTerminalId = event.terminalId

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
        spec = if (eventTerminalId != null) spec.bind("terminalId", eventTerminalId)
        else spec.bindNull("terminalId", UUID::class.java)

        val applyMono: Mono<Void> = spec.fetch().rowsUpdated().then()

        if (terminalId == null) {
            applyMono
                .doOnSuccess { publishComplete(eventId, mapOf("debtId" to event.debtId.toString(), "status" to "OPEN")) }
                .doOnError { e -> publishFailed(eventId, e.message ?: "Save error") }
                .subscribe()
        } else {
            val tid = terminalId
            watermark.applyInOrder(tid, seq, "DebtCreated", rawJson, emptyMap(), applyMono)
                .doOnSuccess { result ->
                    when (result) {
                        WatermarkProcessor.ApplyResult.APPLIED, WatermarkProcessor.ApplyResult.ALREADY_APPLIED -> {
                            log.info("Debt saved [seq={}]: {}", seq, event.debtId)
                            publishComplete(eventId, mapOf("debtId" to event.debtId.toString(), "status" to "OPEN"))
                        }
                        WatermarkProcessor.ApplyResult.DEFERRED -> {
                            log.info("Debt deferred [seq={}]: {}", seq, event.debtId)
                            publishPending(eventId, mapOf("debtId" to event.debtId.toString(), "seq" to seq.toString()))
                        }
                    }
                }
                .doOnError { e -> publishFailed(eventId, e.message ?: "Save error") }
                .subscribe()
        }
    }

    private fun handleDebtRecovered(event: DebtRecoveredEvent, eventId: UUID, terminalId: UUID?, seq: Long, rawJson: String) {
        log.info("Processing DebtRecoveredEvent: debtId={} seq={}", event.debtId, seq)

        val applyMono: Mono<Void> = debtRepository.findById(event.debtId)
            .switchIfEmpty(Mono.error(IllegalStateException("Debt not found: ${event.debtId}")))
            .flatMap { existing ->
                val sql = """UPDATE ASOP_CARD_DEBTS
                    SET DEBT_STATUS = 'RECOVERED', RECOVERED_AT = :recoveredAt, UPDATED_AT = :updatedAt
                    WHERE DEBT_ID = :debtId"""
                db.sql(sql)
                    .bind("recoveredAt", Instant.now())
                    .bind("updatedAt", Instant.now())
                    .bind("debtId", event.debtId)
                    .fetch().rowsUpdated().then()
            }

        if (terminalId == null) {
            applyMono
                .doOnSuccess { publishComplete(eventId, mapOf("debtId" to event.debtId.toString(), "status" to "RECOVERED")) }
                .doOnError { e -> publishFailed(eventId, e.message ?: "Recover error") }
                .subscribe()
        } else {
            val tid = terminalId
            watermark.applyInOrder(tid, seq, "DebtRecovered", rawJson, emptyMap(), applyMono)
                .doOnSuccess { result ->
                    when (result) {
                        WatermarkProcessor.ApplyResult.APPLIED, WatermarkProcessor.ApplyResult.ALREADY_APPLIED -> {
                            log.info("Debt recovered [seq={}]: {}", seq, event.debtId)
                            publishComplete(eventId, mapOf("debtId" to event.debtId.toString(), "status" to "RECOVERED"))
                        }
                        WatermarkProcessor.ApplyResult.DEFERRED -> {
                            log.info("Debt-recover deferred [seq={}]: {}", seq, event.debtId)
                            publishPending(eventId, mapOf("debtId" to event.debtId.toString(), "seq" to seq.toString()))
                        }
                    }
                }
                .doOnError { e -> publishFailed(eventId, e.message ?: "Recover error") }
                .subscribe()
        }
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
        val record = ProducerRecord(KafkaTopic.DEBT_EVENTS, eventId.toString(), result as Any)
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

    private fun parseTerminalId(json: String): UUID? = try {
        val node = objectMapper.readTree(json)
        val s = node.get("terminalId")?.asText() ?: return null
        UUID.fromString(s)
    } catch (_: Exception) { null }
}
