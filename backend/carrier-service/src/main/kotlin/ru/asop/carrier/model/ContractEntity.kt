package ru.asop.carrier.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Table("ASOP_CONTRACTS")
data class ContractEntity(
    @Id val contractId: UUID,
    val contractorType: String? = null,
    val carrierId: UUID? = null,
    val cardsDistributorId: UUID? = null,
    val contractNumber: String,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val status: String = "ACTIVE",
    val commissionPercent: java.math.BigDecimal? = null,
    val attributes: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
)
