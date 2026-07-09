package ru.asop.common.event

import java.time.Instant
import java.util.UUID

/**
 * Базовый интерфейс для всех доменных событий (Kafka).
 */
interface DomainEvent {
    val eventId: UUID
    val eventType: String
    val aggregateId: UUID
    val aggregateType: String
    val occurredAt: Instant
    val causationId: UUID?
    val correlationId: UUID
    val userId: UUID?
    val version: Int
}