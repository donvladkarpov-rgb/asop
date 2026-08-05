package ru.asop.orchestrator.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import ru.asop.kafka.events.delta.DeltaSyncCommand
import ru.asop.orchestrator.service.DeltaSyncService
import java.util.UUID

@Component
class DeltaCommandConsumer(
    private val deltaSyncService: DeltaSyncService,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.delta-commands}"], groupId = "orchestrator-delta")
    fun onCommand(payload: String, @Header("X-Event-Id") eventId: String?) {
        try {
            val command = objectMapper.readValue(payload, DeltaSyncCommand::class.java)
            val resolvedEventId = eventId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: command.eventId
            log.debug("Delta command received: eventId={}, terminal={}, lastVersion={}",
                resolvedEventId, command.terminalId, command.lastVersion)
            deltaSyncService.process(command.copy(eventId = resolvedEventId)).subscribe(
                { log.info("Delta processed for event {}", resolvedEventId) },
                { err -> log.error("Delta processing failed for event {}", resolvedEventId, err) }
            )
        } catch (e: Exception) {
            log.error("Failed to parse delta command: {}", e.message, e)
        }
    }
}
