package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("ASOP_EVENT_TYPES")
data class EventTypeEntity(
    @Id
    val eventType: String,
    val eventTypeName: String,
    val createdAt: java.time.Instant = java.time.Instant.now(),
    val updatedAt: java.time.Instant = java.time.Instant.now(),
    val deletedAt: java.time.Instant? = null,
    val version: Long? = null
)
