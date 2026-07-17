package ru.asop.card.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import ru.asop.card.model.CardEntity
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
        val eventId = parseEventId(eventIdHeader)

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

        val entity = CardEntity(
            cardId = event.cardId,
            cardTypeId = event.cardTypeId,
            userId = event.ownerUserId,
            isPrimary = event.isPrimary,
            registeredAt = event.registeredAt,
            createdAt = event.occurredAt,
            updatedAt = event.occurredAt
        )
        cardRepository.save(entity)
            .doOnSuccess {
                log.info("Card saved: {}", it.cardId)
                publishComplete(eventId, mapOf("cardId" to it.cardId.toString()))
            }
            .doOnError { e ->
                log.error("Failed to save card: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Save error")
            }
            .subscribe()
    }

    private fun handleCardBlocked(event: CardBlockedEvent, eventId: UUID) {
        log.info("Processing CardBlockedEvent: cardId={}, blockType={}", event.cardId, event.blockType)

        cardRepository.findById(event.cardId)
            .flatMap { existing ->
                cardRepository.save(existing)
            }
            .doOnSuccess {
                log.info("Card blocked: {}", event.cardId)
                publishComplete(eventId, mapOf("cardId" to event.cardId.toString(), "status" to "BLOCKED"))
            }
            .doOnError { e ->
                log.error("Failed to block card: {}", e.message, e)
                publishFailed(eventId, e.message ?: "Block error")
            }
            .subscribe()
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

    private fun parseEventId(header: ByteArray?): UUID {
        if (header == null) return UUID.randomUUID()
        return try {
            UUID.fromString(String(header))
        } catch (e: IllegalArgumentException) {
            UUID.randomUUID()
        }
    }
}
