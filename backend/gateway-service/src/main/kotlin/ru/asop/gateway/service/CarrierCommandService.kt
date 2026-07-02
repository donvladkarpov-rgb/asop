package ru.asop.gateway.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kafka.sender.SenderResult  // ← НОВОЕ
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.InnValidator
import ru.asop.common.util.UuidUtils
import ru.asop.api.gateway.dto.request.CarrierCreateRequest
import ru.asop.kafka.events.carrier.CarrierCreatedEvent
import java.security.Principal
import java.time.Instant
import java.util.UUID

@Service
class CarrierCommandService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun createCarrier(request: CarrierCreateRequest, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            if (!InnValidator.isValid(request.inn)) {
                throw IllegalArgumentException("Invalid INN: ${request.inn}")
            }

            val eventId = UuidUtils.newId()
            val carrierId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()
            val userId = extractUserId(principal)

            val event = CarrierCreatedEvent(
                carrierId = carrierId,
                carrierName = request.carrierName,
                inn = request.inn,
                regionId = request.regionId,
                createdAt = Instant.now(),
                correlationId = correlationId,
                userId = userId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.CARRIER_COMMANDS,
                event.carrierId.toString(),
                event as Any
            )

            kafkaTemplate.send(record)
                .doOnSuccess { result: SenderResult<*> ->  // ← явный тип для ясности
                    log.info(
                        "Sent {} to topic={}, eventId={}, carrierId={}, partition={}, offset={}",
                        event.eventType,
                        KafkaTopic.CARRIER_COMMANDS,
                        eventId,
                        event.carrierId,
                        result.recordMetadata().partition(),
                        result.recordMetadata().offset()
                    )
                }
                .doOnError { error ->
                    log.error(
                        "Failed to send {} to Kafka: {}",
                        event.eventType,
                        error.message,
                        error
                    )
                }
                .thenReturn(eventId)
        }
    }

    private fun extractUserId(principal: Principal?): UUID? {
        if (principal == null) return null
        return try {
            UuidUtils.parseOrNull(principal.name)
        } catch (e: Exception) {
            log.warn("Failed to parse userId from principal: {}", principal.name)
            null
        }
    }
}