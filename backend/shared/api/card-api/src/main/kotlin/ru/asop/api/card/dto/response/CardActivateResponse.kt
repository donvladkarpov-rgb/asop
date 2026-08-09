package ru.asop.api.card.dto.response

import java.time.Instant
import java.util.UUID

data class CardActivateResponse(
    val cardId: UUID,
    val cardRole: String,
    val registeredAt: Instant
)
