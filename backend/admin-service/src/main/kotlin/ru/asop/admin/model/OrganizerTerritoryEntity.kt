package ru.asop.admin.model

import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_ORGANIZER_TERRITORIES")
data class OrganizerTerritoryEntity(
    val organizerId: UUID,
    val territoryId: UUID,
    val createdAt: java.time.Instant = java.time.Instant.now(),
    val updatedAt: java.time.Instant = java.time.Instant.now(),
    val deletedAt: java.time.Instant? = null,
    val version: Long? = null
)
