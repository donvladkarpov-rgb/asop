package ru.asop.payment.kafka

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import ru.asop.kafka.events.payment.PaymentAuthorizedEvent
import ru.asop.kafka.events.payment.PaymentFailedEvent

/**
 * Публикация событий платежей в `asop.payment.events`.
 * Gateway `CommandEventConsumer` завершает pending-событие по `X-Event-Id`.
 */
@Component
class PaymentEventPublisher(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Value("\${asop.kafka.topics.payment-events:asop.payment.events}")
    private lateinit var topic: String

    fun publishAuthorized(event: PaymentAuthorizedEvent, eventId: String?): Mono<Void> =
        publish(event.aggregateId.toString(), event, eventId)

    fun publishFailed(event: PaymentFailedEvent, eventId: String?): Mono<Void> =
        publish(event.aggregateId.toString(), event, eventId)

    private fun publish(key: String, payload: Any, eventId: String?): Mono<Void> {
        val record = ProducerRecord<String, Any>(topic, key, payload)
        if (eventId != null) {
            record.headers().add("X-Event-Id", eventId.toByteArray())
        }
        return kafkaTemplate.send(record)
            .doOnSuccess { log.debug("Published payment event: key={}, topic={}", key, topic) }
            .doOnError { log.error("Failed to publish payment event: key={}, error={}", key, it.message) }
            .then()
    }
}
