package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.NotNull
import java.util.UUID

data class OrganizerTerritoryAssignRequest(
    @field:NotNull
    val organizerId: UUID,

    @field:NotNull
    val territoryId: UUID
)
