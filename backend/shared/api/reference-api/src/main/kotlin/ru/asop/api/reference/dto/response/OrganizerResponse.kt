package ru.asop.api.reference.dto.response

import java.util.UUID

data class OrganizerResponse(
    val id: UUID,
    val organizerName: String
)
