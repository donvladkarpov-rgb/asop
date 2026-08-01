package ru.asop.orchestrator.kafka

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import ru.asop.kafka.events.delta.DeltaSyncCommand
import ru.asop.orchestrator.service.DeltaSyncService
import java.util.UUID

@Component
class DeltaCommandConsumer(
    private val deltaSyncService: DeltaSyncService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.delta-commands}"], groupId = "orchestrator-delta")
    fun onCommand(command: DeltaSyncCommand, @Header("X-Event-Id") eventId: String?) {
        val resolvedEventId = eventId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: command.eventId
        log.debug("Delta command received: eventId={}, terminal={}, tables={}",
            resolvedEventId, command.terminalId, command.lastUpdatedAt.size)
        deltaSyncService.process(command.copy(eventId = resolvedEventId)).subscribe(
            { log.info("Delta processed for event {}", resolvedEventId) },
            { err -> log.error("Delta processing failed for event {}", resolvedEventId, err) }
        )
    }
}
