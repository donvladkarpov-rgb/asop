package ru.asop.card.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_CARD_BANKS")
data class CardBankEntity(
    @Id
    val cardId: UUID,
    val panToken: String,
    val panLast4: String? = null,
    val bin: String? = null,
    val isTokenized: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)
