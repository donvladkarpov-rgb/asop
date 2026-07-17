package ru.asop.card.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import ru.asop.card.repository.CardRepository
import ru.asop.common.kafka.KafkaTopic
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.card.CardBlockedEvent
import ru.asop.kafka.events.card.CardRegisteredEvent
import java.time.Instant
import java.util.UUID

@Component
class CardCommandConsumer(
    private val cardRepository: CardRepository,
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.card-commands}"])
    fun handleCommand(
        json: String,
        @Header(name = "X-Event-Id", required = false) eventIdHeader: ByteArray?
    ) {
        log.debug("Received card command: {}", json)
        val eventId = parseEventId(eventIdHeader, json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "CardRegistered" -> {
                    val event = objectMapper.treeToValue(node, CardRegisteredEvent::class.java)
                    handleCardRegistered(event, eventId)
                }
                "CardBlocked" -> {
                    val event = objectMapper.treeToValue(node, CardBlockedEvent::class.java)
                    handleCardBlocked(event, eventId)
                }
                else -> log.warn("Unknown card event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize card command: {}", e.message, e)
            publishFailed(eventId, e.message ?: "Deserialization error")
        }
    }

    private fun handleCardRegistered(event: CardRegisteredEvent, eventId: UUID) {
        log.info("Processing CardRegisteredEvent: cardId={}", event.cardId)

        val sql = """INSERT INTO ASOP_CARDS 
            (CARD_ID, CARD_TYPE_ID, USER_ID, IS_PRIMARY, REGISTERED_AT, CREATED_AT, UPDATED_AT) 
            VALUES (:cardId, :cardTypeId, :userId, :isPrimary, :registeredAt, :createdAt, :updatedAt)"""

        val now = Instant.now()
        val ownerUserId = event.ownerUserId
        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
            .bind("cardId", event.cardId)
            .bind("cardTypeId", event.cardTypeId)
            .bind("isPrimary", event.isPrimary)
            .bind("registeredAt", event.registeredAt)
            .bind("createdAt", now)
            .bind("updatedAt", now)
        spec = if (ownerUserId != null) spec.bind("userId", ownerUserId)
        else spec.bindNull("userId", UUID::class.java)

        spec.fetch().rowsUpdated()
            .doOnSuccess {
                log.info("Card saved: {}", event.cardId)
                publishComplete(eventId, mapOf("cardId" to event.cardId.toString()))
            }
            .doOnError { e ->
                log.error("Failed to save card: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Save error")
            }
            .subscribe()
    }

    private fun handleCardBlocked(event: CardBlockedEvent, eventId: UUID) {
        log.info("Processing CardBlockedEvent: cardId={}, blockType={}", event.cardId, event.blockType)
        publishFailed(eventId, "Card block not implemented: ASOP_CARDS has no STATUS/IS_BLOCKED column. " +
            "Implement ASOP_CARD_BLOCKS table or add STATUS column to ASOP_CARDS.")
    }

    private fun publishComplete(eventId: UUID, data: Map<String, String>) {
        val json = objectMapper.writeValueAsString(data)
        val result = CommandResult(eventId = eventId, status = "COMPLETED", resultData = json)
        val record = ProducerRecord(KafkaTopic.CARD_EVENTS, eventId.toString(), result as Any)
        record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
        kafkaTemplate.send(record).subscribe()
    }

    private fun publishFailed(eventId: UUID, errorMessage: String) {
        val result = CommandResult(eventId = eventId, status = "FAILED", errorMessage = errorMessage)
        val record = ProducerRecord(KafkaTopic.CARD_EVENTS, eventId.toString(), result as Any)
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
