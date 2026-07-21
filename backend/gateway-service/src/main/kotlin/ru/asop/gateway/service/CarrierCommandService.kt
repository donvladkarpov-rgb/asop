package ru.asop.gateway.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kafka.sender.SenderResult
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
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val eventService: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun createCarrier(request: CarrierCreateRequest, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            val inn = request.inn.trim()
            if (!InnValidator.isValid(inn)) {
                throw IllegalArgumentException("Invalid INN: $inn")
            }

            val eventId = UuidUtils.newId()
            val carrierId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()
            val keycloakId = principal?.name

            val event = CarrierCreatedEvent(
                carrierId = carrierId,
                carrierName = request.carrierName.trim(),
                inn = inn,
                regionId = request.regionId,
                createdAt = Instant.now(),
                correlationId = correlationId,
                userId = null // сервис резолвит userId из keycloakId по БД
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.CARRIER_COMMANDS,
                event.carrierId.toString(),
                event as Any
            )

            // Pass-Through Identity: keycloakId в Kafka headers
            val keycloakId = principal?.name
            if (keycloakId != null) {
                record.headers().add("X-Keycloak-Id", keycloakId.encodeToByteArray())
            }

            eventService.createPending(eventId, KafkaTopic.CARRIER_COMMANDS)

            kafkaTemplate.send(record)
                .doOnSuccess { result: SenderResult<*> ->
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
                    eventService.fail(eventId, error.message ?: "Unknown error")
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
}
