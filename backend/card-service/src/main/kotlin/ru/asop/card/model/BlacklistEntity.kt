package ru.asop.card.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_BLACKLISTS")
data class BlacklistEntity(
    @Id
    val cardId: UUID,
    val blockType: String,
    val blockedAt: Instant = Instant.now(),
    val relatedDebtId: UUID? = null,
    val autoUnblockOnRecovery: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)
