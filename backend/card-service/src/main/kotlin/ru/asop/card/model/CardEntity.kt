package ru.asop.card.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_CARDS")
data class CardEntity(
    @Id
    val cardId: UUID,
    val cardTypeId: UUID,
    val userId: UUID?,
    val isPrimary: Boolean = false,
    val registeredAt: Instant?,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
