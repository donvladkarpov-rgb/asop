package ru.asop.orchestrator.kafka

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import ru.asop.kafka.events.delta.FullSyncCommand
import ru.asop.orchestrator.service.FullSyncService
import java.util.UUID

@Component
class FullSyncCommandConsumer(
    private val fullSyncService: FullSyncService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["\${asop.kafka.topics.delta-full-commands}"], groupId = "orchestrator-delta-full")
    fun onCommand(command: FullSyncCommand, @Header("X-Event-Id") eventId: String?) {
        val resolvedEventId = eventId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: command.eventId
        log.debug("Full sync command received: eventId={}, terminal={}", resolvedEventId, command.terminalId)
        fullSyncService.process(command.copy(eventId = resolvedEventId)).subscribe(
            { log.info("Full sync processed for event {}", resolvedEventId) },
            { err -> log.error("Full sync processing failed for event {}", resolvedEventId, err) }
        )
    }
}
