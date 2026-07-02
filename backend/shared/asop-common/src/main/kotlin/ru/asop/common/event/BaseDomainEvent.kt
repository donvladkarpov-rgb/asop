package ru.asop.common.event

import com.github.f4b6a3.uuid.UuidCreator
import java.time.Instant
import java.util.UUID

/**
 * Базовая реализация DomainEvent для Kafka-событий.
 */
abstract class BaseDomainEvent(
    override val eventId: UUID = UuidCreator.getTimeOrderedEpoch(),
    override val aggregateId: UUID = UuidCreator.getTimeOrderedEpoch(),
    override val aggregateType: String,
    override val causationId: UUID? = null,
    override val correlationId: UUID = UuidCreator.getTimeOrderedEpoch(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : DomainEvent {

    override val eventType: String
        get() = this::class.simpleName?.removeSuffix("Event")
            ?: error("Event class must have a name")
}