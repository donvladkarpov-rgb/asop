package ru.asop.api.reference.dto.response

import java.util.UUID

data class OrganizerTerritoryResponse(
    val organizerId: UUID,
    val territoryId: UUID,
    val territoryName: String? = null,
    val regionName: String? = null
)
