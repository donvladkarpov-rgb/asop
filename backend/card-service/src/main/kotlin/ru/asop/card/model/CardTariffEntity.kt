package ru.asop.card.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("ASOP_CARD_TARIFFS")
data class CardTariffEntity(
    @Id
    val cardTariffId: UUID,
    val cardId: UUID,
    val tariffTypeId: UUID,
    val balance: BigDecimal? = null,
    val travelCount: Int? = null,
    val maxTravelCount: Int? = null,
    val expirationDate: Instant? = null,
    val activatedAt: Instant? = null,
    val purchaseTransactionId: UUID? = null,
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)
