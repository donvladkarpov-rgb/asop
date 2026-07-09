package ru.asop.kafka.events.user

import ru.asop.common.event.BaseDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Событие создания пользователя.
 */
data class UserCreatedEvent(
    val createdUserId: UUID,         // ← было userId, переименовали
    val firstName: String,
    val lastNameInitial: String,
    val patronymicInitial: String?,
    val phone: String?,
    val keycloakId: String?,
    val createdAt: Instant,

    override val aggregateType: String = "User",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,  // ← это из BaseDomainEvent (админ, который создал)
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = createdUserId,      // ← передаём createdUserId как aggregateId
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)