package ru.asop.card.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.asop.card.model.CardEntity
import ru.asop.card.repository.CardRepository
import ru.asop.kafka.events.card.CardRegisteredEvent
import ru.asop.kafka.events.card.CardBlockedEvent

@Component
class CardCommandConsumer(
    private val cardRepository: CardRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.card-commands}"])
    fun handleCommand(json: String) {
        log.debug("Received card command: {}", json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "CardRegistered" -> {
                    val event = objectMapper.treeToValue(node, CardRegisteredEvent::class.java)
                    handleCardRegistered(event)
                }
                "CardBlocked" -> {
                    val event = objectMapper.treeToValue(node, CardBlockedEvent::class.java)
                    handleCardBlocked(event)
                }
                else -> log.warn("Unknown card event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize card command: {}", e.message, e)
        }
    }

    private fun handleCardRegistered(event: CardRegisteredEvent) {
        log.info("Processing CardRegisteredEvent: cardId={}", event.cardId)

        val entity = CardEntity(
            cardId = event.cardId,
            cardTypeId = event.cardTypeId,
            userId = event.userId,
            isPrimary = false,
            registeredAt = event.registeredAt,
            createdAt = event.occurredAt,
            updatedAt = event.occurredAt
        )
        cardRepository.save(entity)
            .doOnSuccess { log.info("Card saved: {}", it.cardId) }
            .doOnError { e -> log.error("Failed to save card: {}", e.message, e) }
            .subscribe()
    }

    private fun handleCardBlocked(event: CardBlockedEvent) {
        log.info("Processing CardBlockedEvent: cardId={}", event.cardId)
    }
}
