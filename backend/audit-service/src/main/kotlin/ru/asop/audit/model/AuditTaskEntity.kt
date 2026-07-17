package ru.asop.audit.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_AUDIT_TASKS")
data class AuditTaskEntity(
    @Id
    val taskId: UUID,
    val taskNumber: String,
    val issuerType: String = "CARRIER",
    val organizerId: UUID? = null,
    val carrierId: UUID? = null,
    val assignedAuditServiceId: UUID? = null,
    val taskStartDate: Instant = Instant.now(),
    val taskEndDate: Instant? = null,
    val status: String = "DRAFT",
    val description: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
