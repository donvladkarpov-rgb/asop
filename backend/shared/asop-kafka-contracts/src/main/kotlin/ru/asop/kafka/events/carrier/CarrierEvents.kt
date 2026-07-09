package ru.asop.kafka.events.carrier

import ru.asop.common.event.BaseDomainEvent
import java.time.Instant
import java.util.UUID

/**
 * Событие создания перевозчика.
 * Публикуется в топик: asop.carrier.events
 */
data class CarrierCreatedEvent(
    val carrierId: UUID,
    val carrierName: String,
    val inn: String,
    val regionId: UUID,
    val createdAt: Instant,

    // Базовые поля DomainEvent
    override val aggregateType: String = "Carrier",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = carrierId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)

/**
 * Событие обновления перевозчика.
 */
data class CarrierUpdatedEvent(
    val carrierId: UUID,
    val carrierName: String?,
    val inn: String?,
    val regionId: UUID?,
    val updatedAt: Instant,

    override val aggregateType: String = "Carrier",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = carrierId,
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)