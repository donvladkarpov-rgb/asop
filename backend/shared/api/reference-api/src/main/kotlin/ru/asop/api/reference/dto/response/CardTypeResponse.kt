package ru.asop.api.reference.dto.response

import java.util.UUID

data class CardTypeResponse(
    val id: UUID,
    val cardTypeName: String
)
