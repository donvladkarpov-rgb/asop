package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_SESSION_TYPES")
data class SessionTypeEntity(
    @Id
    val sessionTypeId: UUID,
    val sessionTypeCode: String,
    val sessionTypeName: String,
    val createdAt: java.time.Instant = java.time.Instant.now(),
    val updatedAt: java.time.Instant = java.time.Instant.now(),
    val deletedAt: java.time.Instant? = null,
    val version: Long? = null
)
