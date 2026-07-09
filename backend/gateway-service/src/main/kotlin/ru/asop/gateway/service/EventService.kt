package ru.asop.gateway.service

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.asop.gateway.model.EventState
import ru.asop.gateway.model.EventStatus
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class EventService {

    private val log = LoggerFactory.getLogger(javaClass)

    private val store = ConcurrentHashMap<UUID, EventStatus>()

    private val ttlMinutes = 30L

    fun createPending(eventId: UUID, commandTopic: String): EventStatus {
        val status = EventStatus(
            eventId = eventId,
            commandTopic = commandTopic,
            state = EventState.PENDING
        )
        store[eventId] = status
        log.debug("Event created: {} on topic {}", eventId, commandTopic)
        return status
    }

    fun complete(eventId: UUID, resultData: String? = null) {
        store.computeIfPresent(eventId) { _, existing ->
            existing.copy(
                state = EventState.COMPLETED,
                resultData = resultData ?: existing.resultData,
                completedAt = Instant.now()
            )
        } ?: log.warn("Attempted to complete unknown event: {}", eventId)
    }

    fun fail(eventId: UUID, errorMessage: String) {
        store.computeIfPresent(eventId) { _, existing ->
            existing.copy(
                state = EventState.FAILED,
                errorMessage = errorMessage,
                completedAt = Instant.now()
            )
        } ?: log.warn("Attempted to fail unknown event: {}", eventId)
    }

    fun getStatus(eventId: UUID): Optional<EventStatus> {
        return Optional.ofNullable(store[eventId])
    }

    @PostConstruct
    fun startCleanup() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
            {
                val cutoff = Instant.now().minusSeconds(ttlMinutes * 60)
                store.entries.removeIf { it.value.createdAt.isBefore(cutoff) }
            },
            ttlMinutes,
            ttlMinutes,
            TimeUnit.MINUTES
        )
        log.info("Event cleanup scheduled every {} minutes", ttlMinutes)
    }
}
