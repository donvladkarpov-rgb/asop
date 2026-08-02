package ru.asop.card.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("ASOP_TARIFF_RATES")
data class TariffRateEntity(
    @Id
    val tariffRateId: UUID,
    val tariffTypeId: UUID,
    val carrierId: UUID? = null,
    val zoneId: UUID? = null,
    val pathId: UUID? = null,
    val price: BigDecimal,
    val description: String? = null,
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)
