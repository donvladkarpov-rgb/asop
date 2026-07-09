package ru.asop.api.reference.dto.response

import java.util.UUID

data class SessionTypeResponse(
    val id: UUID,
    val sessionTypeCode: String,
    val sessionTypeName: String
)
