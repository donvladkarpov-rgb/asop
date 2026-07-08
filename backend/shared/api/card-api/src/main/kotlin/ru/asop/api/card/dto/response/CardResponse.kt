package ru.asop.api.card.dto.response

import java.time.Instant
import java.util.UUID

data class CardResponse(
    val id: UUID,
    val cardTypeId: UUID,
    val userId: UUID?,
    val isPrimary: Boolean,
    val registeredAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)
