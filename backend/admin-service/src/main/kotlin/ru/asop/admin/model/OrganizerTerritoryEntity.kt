package ru.asop.admin.model

import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_ORGANIZER_TERRITORIES")
data class OrganizerTerritoryEntity(
    val organizerId: UUID,
    val territoryId: UUID
)
