package ru.asop.gateway.service

import ru.asop.gateway.kafka.addTerminalSeq

import ru.asop.common.event.EventService


import org.apache.kafka.clients.producer.ProducerRecord

import org.slf4j.LoggerFactory

import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate

import org.springframework.stereotype.Service

import reactor.core.publisher.Mono

import reactor.kafka.sender.SenderResult

import ru.asop.common.kafka.KafkaTopic

import ru.asop.common.util.UuidUtils

import ru.asop.kafka.events.audit.AuditTaskCreatedEvent

import ru.asop.api.audit.dto.request.AuditTaskCreateRequest

import java.security.Principal

import java.time.Instant

import java.util.UUID


@Service
class AuditCommandService(
    private val kafkaTemplate: ReactiveKafkaProducerTemplate<String, Any>,
    private val eventService: EventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun ProducerRecord<String, Any>.addContext(regionId: UUID?, carrierId: UUID?, timezone: String?) {
        headers().add("X-Carrier-Id", carrierId?.toString()?.encodeToByteArray())
        headers().add("X-Region-Id", regionId?.toString()?.encodeToByteArray())
        headers().add("X-Timezone", timezone?.encodeToByteArray())
    }

    fun createTask(request: AuditTaskCreateRequest, principal: Principal?, seq: Long = 0L): Mono<UUID> {
        return Mono.fromCallable {
            val eventId = UuidUtils.newId()
            val taskId = UuidUtils.newId()
            val correlationId = UuidUtils.newId()

            val event = AuditTaskCreatedEvent(
                taskId = taskId,
                taskNumber = request.taskNumber,
                organizerId = request.organizerId,
                carrierId = request.carrierId,
                description = request.description,
                correlationId = correlationId
            )

            eventId to event
        }.flatMap { (eventId, event) ->
            val record = ProducerRecord(
                KafkaTopic.AUDIT_COMMANDS,
                event.taskId?.toString() ?: event.aggregateId.toString(),
                event as Any
            )
            record.headers().add("X-Event-Id", eventId.toString().encodeToByteArray())
            record.addContext(request.regionId, request.carrierId, request.timezone)
            record.addTerminalSeq(seq)

            eventService.createPending(eventId, KafkaTopic.AUDIT_COMMANDS)
                .then(kafkaTemplate.send(record))
                .doOnSuccess { result: SenderResult<*> ->
                    log.info(
                        "Sent AuditTaskCreated to topic={}, eventId={}, taskId={}",
                        KafkaTopic.AUDIT_COMMANDS, eventId, event.taskId
                    )
                }
                .doOnError { error ->
                    eventService.fail(eventId, error.message ?: "Unknown error").subscribe()
                    log.error("Failed to send AuditTaskCreated to Kafka: {}", error.message, error)
                }
                .thenReturn(eventId)
        }
    }
}
