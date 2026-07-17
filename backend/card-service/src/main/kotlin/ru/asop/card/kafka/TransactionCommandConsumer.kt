package ru.asop.card.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import ru.asop.card.repository.TransactionCardRepository
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.transaction.TransactionCompletedEvent
import java.util.UUID

@Component
class TransactionCommandConsumer(
    private val db: DatabaseClient,
    private val transactionCardRepository: TransactionCardRepository,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.transaction-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?
    ) {
        log.debug("Received transaction command: {}", json)
        val eventId = parseEventId(eventIdHeader, json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "TransactionCompleted" -> {
                    val event = objectMapper.treeToValue(node, TransactionCompletedEvent::class.java)
                    handleTransactionCompleted(event, eventId)
                }
                else -> log.warn("Unknown transaction event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize transaction command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun handleTransactionCompleted(event: TransactionCompletedEvent, eventId: UUID) {
        log.info("Processing TransactionCompletedEvent: transactionId={}", event.transactionId)

        val metadata = event.metadata
        val metadataExpr = if (metadata.isNullOrEmpty()) "NULL" else "CAST(:metadata AS jsonb)"

        val sql = """INSERT INTO ASOP_TRANSACTIONS 
            (TRANSACTION_ID, STARTED_AT, COMPLETED_AT, SESSION_ID, TRANSACTION_TYPE_ID, 
             TRANSACTION_RESULT_ID, AMOUNT, CURRENCY, METADATA) 
            VALUES (:transactionId, :startedAt, :completedAt, :sessionId, :transactionTypeId, 
                    :transactionResultId, :amount, :currency, $metadataExpr)"""

        val spec = db.sql(sql)
            .bind("transactionId", event.transactionId)
            .bind("startedAt", event.completedAt)
            .bind("completedAt", event.completedAt)
            .bind("transactionTypeId", event.transactionTypeId)
            .bind("transactionResultId", event.transactionResultId)
            .bind("amount", event.amount)
            .bind("currency", event.currency)

        val boundSpec = if (metadata.isNullOrEmpty()) spec else spec.bind("metadata", metadata)
        val sessionId = event.sessionId
        val sessionSpec = if (sessionId != null) {
            boundSpec.bind("sessionId", sessionId)
        } else {
            boundSpec.bindNull("sessionId", java.util.UUID::class.java)
        }

        sessionSpec.fetch().rowsUpdated()
            .flatMap {
                val cardId = event.cardId
                if (cardId != null) {
                    val tceSql = """INSERT INTO ASOP_TRANSACTION_CARDS 
                        (TRANSACTION_CARD_ID, TRANSACTION_ID, CARD_ID, CARD_ROLE) 
                        VALUES (:tcId, :transactionId, :cardId, 'PAYER')"""
                    db.sql(tceSql)
                        .bind("tcId", UuidUtils.newId())
                        .bind("transactionId", event.transactionId)
                        .bind("cardId", cardId)
                        .fetch().rowsUpdated()
                } else {
                    reactor.core.publisher.Mono.just(1L)
                }
            }
            .doOnSuccess {
                log.info("Transaction saved: {}", event.transactionId)
                publishComplete(eventId, mapOf(
                    "transactionId" to event.transactionId.toString(),
                    "amount" to event.amount.toString()
                ))
            }
            .doOnError { e ->
                log.error("Failed to save transaction: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Save error")
            }
            .subscribe()
    }

    private fun publishComplete(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "COMPLETED", resultData = json)
        val record = ProducerRecord(KafkaTopic.TRANSACTION_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        kafkaTemplate.send(record).subscribe()
    }

    private fun publishFailed(eventId: UUID, errorMessage: String) {
        val result = CommandResult(eventId = eventId, status = "FAILED", errorMessage = errorMessage)
        val record = ProducerRecord(KafkaTopic.TRANSACTION_EVENTS, eventId.toString(), result as Any)
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
