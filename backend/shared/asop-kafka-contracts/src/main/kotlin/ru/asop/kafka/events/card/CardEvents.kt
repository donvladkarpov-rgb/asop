package ru.asop.kafka.events.card

import ru.asop.common.event.BaseDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Событие регистрации карты.
 */
data class CardRegisteredEvent(
    val cardId: UUID,
    val cardTypeId: UUID,
    val ownerUserId: UUID?,          // ← было userId, переименовали
    val isPrimary: Boolean,
    val registeredAt: Instant,

    override val aggregateType: String = "Card",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,  // ← это из BaseDomainEvent (кто инициировал)
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = cardId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)

/**
 * Событие блокировки карты.
 */
data class CardBlockedEvent(
    val cardId: UUID,
    val blockType: String,
    val reason: String?,
    val blockedAt: Instant,

    override val aggregateType: String = "Card",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = cardId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)