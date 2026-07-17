package ru.asop.kafka.events.audit

import ru.asop.common.event.BaseDomainEvent
import java.time.Instant
import java.util.UUID

data class AuditTaskCreatedEvent(
    val taskId: UUID? = null,
    val taskNumber: String,
    val organizerId: UUID? = null,
    val carrierId: UUID? = null,
    val terminalId: UUID? = null,
    val description: String? = null,

    override val aggregateType: String = "AuditTask",
    override val causationId: UUID? = null,
    override val correlationId: UUID = UUID.randomUUID(),
    override val userId: UUID? = null,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now()
) : BaseDomainEvent(
    aggregateId = taskId ?: UUID.randomUUID(),
    aggregateType = aggregateType,
    causationId = causationId,
    correlationId = correlationId,
    userId = userId,
    version = version,
    occurredAt = occurredAt
)
