package ru.asop.carrier.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import ru.asop.carrier.model.CarrierEntity
import ru.asop.carrier.repository.CarrierRepository
import ru.asop.kafka.events.carrier.CarrierCreatedEvent
import ru.asop.kafka.events.carrier.CarrierUpdatedEvent
import java.util.UUID

@Component
class CarrierCommandConsumer(
    private val carrierRepository: CarrierRepository,
    private val r2dbcEntityTemplate: R2dbcEntityTemplate,
    private val eventPublisher: CarrierEventPublisher,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.carrier-commands}"])
    fun handleCommand(record: ConsumerRecord<String, String>) {
        val json = record.value()
        val eventId = extractEventId(record)
        log.debug("Received carrier command: eventId={}, json={}", eventId, json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "CarrierCreated" -> {
                    val event = objectMapper.treeToValue(node, CarrierCreatedEvent::class.java)
                    handleCarrierCreated(event, eventId)
                }
                "CarrierUpdated" -> {
                    val event = objectMapper.treeToValue(node, CarrierUpdatedEvent::class.java)
                    handleCarrierUpdated(event)
                }
                else -> log.warn("Unknown carrier event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize carrier command: eventId={}, error={}", eventId, e.message, e)
        }
    }

    private fun handleCarrierCreated(event: CarrierCreatedEvent, eventId: UUID?) {
        log.info("Processing CarrierCreatedEvent: carrierId={}", event.carrierId)

        val entity = CarrierEntity(
            carrierId = event.carrierId,
            carrierName = event.carrierName,
            inn = event.inn,
            regionId = event.regionId,
            createdAt = event.createdAt,
            updatedAt = event.createdAt
        )
        r2dbcEntityTemplate.insert(entity)
            .flatMap { saved ->
                if (eventId != null) {
                    eventPublisher.publishSuccess(eventId, saved.carrierId).thenReturn(saved)
                } else {
                    Mono.just(saved)
                }
            }
            .doOnSuccess { log.info("Carrier saved: {}", it.carrierId) }
            .doOnError { e ->
                log.error("Failed to insert carrier: eventId={}, error={}", eventId, e.message, e)
                if (eventId != null) {
                    eventPublisher.publishFailed(eventId, event.carrierId, e.message ?: "Unknown error").subscribe()
                }
            }
            .subscribe()
    }

    private fun handleCarrierUpdated(event: CarrierUpdatedEvent) {
        log.info("Processing CarrierUpdatedEvent: carrierId={}", event.carrierId)

        carrierRepository.findById(event.carrierId)
            .flatMap { existing ->
                val updated = existing.copy(
                    carrierName = event.carrierName ?: existing.carrierName,
                    inn = event.inn ?: existing.inn,
                    regionId = event.regionId ?: existing.regionId,
                    updatedAt = event.updatedAt
                )
                carrierRepository.save(updated)
            }
            .doOnSuccess { log.info("Carrier updated: {}", event.carrierId) }
            .doOnError { e -> log.error("Failed to update carrier: {}", e.message, e) }
            .subscribe()
    }

    private fun extractEventId(record: ConsumerRecord<String, String>): UUID? {
        val header = record.headers().lastHeader("X-Event-Id") ?: return null
        return try {
            UUID.fromString(String(header.value()))
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid X-Event-Id header value: {}", String(header.value()))
            null
        }
    }
}
