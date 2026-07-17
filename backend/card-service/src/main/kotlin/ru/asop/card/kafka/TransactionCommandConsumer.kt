package ru.asop.card.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import ru.asop.card.model.TransactionCardEntity
import ru.asop.card.model.TransactionEntity
import ru.asop.card.repository.TransactionCardRepository
import ru.asop.card.repository.TransactionRepository
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.CommandResult
import ru.asop.kafka.events.transaction.TransactionCompletedEvent
import java.util.UUID

@Component
class TransactionCommandConsumer(
    private val transactionRepository: TransactionRepository,
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
        val eventId = parseEventId(eventIdHeader)

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

        val entity = TransactionEntity(
            transactionId = event.transactionId,
            startedAt = event.completedAt,
            completedAt = event.completedAt,
            sessionId = event.sessionId,
            transactionTypeId = event.transactionTypeId,
            transactionResultId = event.transactionResultId,
            amount = event.amount,
            currency = event.currency,
            metadata = event.metadata
        )

        transactionRepository.save(entity)
            .flatMap { saved ->
                val cardId = event.cardId
                if (cardId != null) {
                    val tce = TransactionCardEntity(
                        transactionCardId = UuidUtils.newId(),
                        transactionId = saved.transactionId,
                        cardId = cardId
                    )
                    transactionCardRepository.save(tce)
                } else {
                    reactor.core.publisher.Mono.just(saved)
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

    private fun parseEventId(header: ByteArray?): UUID {
        if (header == null) return UUID.randomUUID()
        return try {
            UUID.fromString(String(header))
        } catch (e: IllegalArgumentException) {
            UUID.randomUUID()
        }
    }
}
