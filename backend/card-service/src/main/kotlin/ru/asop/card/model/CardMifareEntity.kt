package ru.asop.card.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_CARD_MIFARES")
data class CardMifareEntity(
    @Id
    val cardId: UUID,
    val uid: ByteArray? = null,
    val atqa: Short? = null,
    val sak: Short? = null,
    val protocolVersion: Int? = null,
    val memoryMap: String? = null,
    val cardRole: String = "PASSENGER_ANONYMOUS",
    val certificateSerial: String? = null,
    val publicKeyHash: String? = null,
    val keyVersion: Int = 1,
    val validFrom: Instant? = null,
    val validUntil: Instant? = null,
    val revokedAt: Instant? = null,
    val revocationReason: String? = null,
    val lastAuthAt: Instant? = null,
    val lastAuthTerminal: UUID? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)
