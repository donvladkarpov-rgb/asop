package ru.asop.audit.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_AUDIT_SERVICES")
data class AuditServiceEntity(
    @Id
    val auditServiceId: UUID,
    val serviceCode: String,
    val serviceName: String,
    val issuerType: String,
    val organizerId: UUID? = null,
    val carrierId: UUID? = null,
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)