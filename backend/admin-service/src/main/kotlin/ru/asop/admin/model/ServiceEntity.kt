package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_SERVICES")
data class ServiceEntity(
    @Id
    val serviceId: UUID,
    val serviceName: String,
    val description: String? = null,
    val priority: Int,
    val regionId: UUID,
    val createdAt: java.time.Instant = java.time.Instant.now(),
    val updatedAt: java.time.Instant = java.time.Instant.now(),
    val deletedAt: java.time.Instant? = null,
    val version: Long? = null
)
