package ru.asop.gateway.service

import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.kafka.sender.SenderResult
import ru.asop.common.kafka.KafkaTopic
import ru.asop.common.util.UuidUtils
import ru.asop.kafka.events.gps.GpsPositionReported
import ru.asop.api.gateway.dto.request.GpsPositionReport
import java.security.Principal
import java.util.UUID

@Service
class GpsCommandService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val eventService: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun reportPosition(request: GpsPositionReport, principal: Principal?): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val positionId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()

            val event = GpsPositionReported(
                positionId = positionId,
                vehicleId = request.vehicleId,
                pathId = request.pathId,
                sessionId = request.sessionId,
                latitude = request.latitude,
                longitude = request.longitude,
                speedKmh = request.speedKmh,
                recordedAt = request.recordedAt,
                correlationId = correlationId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.GPS_COMMANDS,
                event.vehicleId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())

            eventService.createPending(eventId, KafkaTopic.GPS_COMMANDS)
                .then(kafkaTemplate.send(record))
                .doOnSuccess { result: SenderResult<*> ->
                    log.info(
                        "Sent GpsPositionReported to topic={}, eventId={}, vehicleId={}",
                        KafkaTopic.GPS_COMMANDS, eventId, event.vehicleId
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error").subscribe()
                    log.error("Failed to send GpsPositionReported to Kafka: {}", error.message, error)
                }
                .thenReturn(eventId)
        }
    }
}
