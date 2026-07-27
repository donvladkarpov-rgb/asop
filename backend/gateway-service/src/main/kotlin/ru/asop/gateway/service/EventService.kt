package ru.asop.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.gateway.model.EventState
import ru.asop.gateway.model.EventStatus
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Service
class EventService(
    private val redis: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val KEY_PREFIX = "asop:event:"
        private val TTL = Duration.ofHours(24)
    }

    private fun redisKey(eventId: UUID): String = KEY_PREFIX + eventId

    fun createPending(eventId: UUID, commandTopic: String): Mono<Void> {
        val status = EventStatus(
            eventId = eventId,
            commandTopic = commandTopic,
            state = EventState.PENDING
        )
        return try {
            val json = objectMapper.writeValueAsString(status)
            redis.opsForValue()
                .set(redisKey(eventId), json, TTL)
                .doOnSuccess {
                    log.debug("Event created in Redis: {} on topic {}", eventId, commandTopic)
                }
                .then()
        } catch (e: Exception) {
            log.error("Failed to serialize EventStatus for {}", eventId, e)
            Mono.error(e)
        }
    }

    fun complete(eventId: UUID, resultData: String? = null): Mono<Void> {
        return getStatus(eventId)
            .flatMap { maybeStatus ->
                if (maybeStatus.isEmpty) {
                    log.warn("Attempted to complete unknown event: {}", eventId)
                    return@flatMap Mono.empty()
                }
                val existing = maybeStatus.get()
                val updated = existing.copy(
                    state = EventState.COMPLETED,
                    resultData = resultData ?: existing.resultData,
                    completedAt = Instant.now()
                )
                saveAndExpire(eventId, updated)
            }
            .then()
    }

    fun fail(eventId: UUID, errorMessage: String): Mono<Void> {
        return getStatus(eventId)
            .flatMap { maybeStatus ->
                if (maybeStatus.isEmpty) {
                    log.warn("Attempted to fail unknown event: {}", eventId)
                    return@flatMap Mono.empty()
                }
                val existing = maybeStatus.get()
                val updated = existing.copy(
                    state = EventState.FAILED,
                    errorMessage = errorMessage,
                    completedAt = Instant.now()
                )
                saveAndExpire(eventId, updated)
            }
            .then()
    }

    fun getStatus(eventId: UUID): Mono<Optional<EventStatus>> {
        return redis.opsForValue().get(redisKey(eventId))
            .map { json ->
                try {
                    Optional.of(objectMapper.readValue<EventStatus>(json))
                } catch (e: Exception) {
                    log.error("Failed to deserialize EventStatus for {}", eventId, e)
                    Optional.empty()
                }
            }
            .defaultIfEmpty(Optional.empty())
    }

    private fun saveAndExpire(eventId: UUID, status: EventStatus): Mono<Void> {
        return try {
            val json = objectMapper.writeValueAsString(status)
            redis.opsForValue()
                .set(redisKey(eventId), json, TTL)
                .then()
        } catch (e: Exception) {
            log.error("Failed to serialize EventStatus for {}", eventId, e)
            Mono.error(e)
        }
    }
}
