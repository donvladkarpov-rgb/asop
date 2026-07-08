package ru.asop.carrier.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import ru.asop.carrier.model.CarrierEntity
import ru.asop.carrier.repository.CarrierRepository
import ru.asop.kafka.events.carrier.CarrierCreatedEvent
import ru.asop.kafka.events.carrier.CarrierUpdatedEvent

@Component
class CarrierCommandConsumer(
    private val carrierRepository: CarrierRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.carrier-commands}"])
    fun handleCommand(json: String) {
        log.debug("Received carrier command: {}", json)

        try {
            val node = objectMapper.readTree(json)
            val eventType = node.get("eventType")?.asText()

            when (eventType) {
                "CarrierCreated" -> {
                    val event = objectMapper.treeToValue(node, CarrierCreatedEvent::class.java)
                    handleCarrierCreated(event)
                }
                "CarrierUpdated" -> {
                    val event = objectMapper.treeToValue(node, CarrierUpdatedEvent::class.java)
                    handleCarrierUpdated(event)
                }
                else -> log.warn("Unknown carrier event type: {}", eventType)
            }
        } catch (e: Exception) {
            log.error("Failed to deserialize carrier command: {}", e.message, e)
        }
    }

    private fun handleCarrierCreated(event: CarrierCreatedEvent) {
        log.info("Processing CarrierCreatedEvent: carrierId={}", event.carrierId)

        val entity = CarrierEntity(
            carrierId = event.carrierId,
            carrierName = event.carrierName,
            inn = event.inn,
            regionId = event.regionId,
            createdAt = event.createdAt,
            updatedAt = event.createdAt
        )
        carrierRepository.save(entity)
            .doOnSuccess { log.info("Carrier saved: {}", it.carrierId) }
            .doOnError { e -> log.error("Failed to save carrier: {}", e.message, e) }
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
}
