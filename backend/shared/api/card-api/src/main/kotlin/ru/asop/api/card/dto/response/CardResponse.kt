package ru.asop.api.card.dto.response

import java.time.Instant
import java.util.UUID

data class CardResponse(
    val id: UUID,
    val cardTypeId: UUID,
    val userId: UUID?,
    /** Если true — UID указывает на MIFARE Classic карту, иначе DESfire. */
    val isClassic: Boolean = false,
    val isPrimary: Boolean,
    /**
     * VCM1 bitmask (промпт 008) для Classic карт. Extracted из
     * ASOP_CARD_MIFARES.IDENTITY_JSON (json field "bitmask", numeric).
     * null для DESfire/PKCS cards или Classic VCM1 without auth.
     */
    val bitmask: Int? = null,
    val registeredAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)
