package ru.asop.api.carrier.dto.response

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ContractResponse(
    val id: UUID,
    val contractorType: String,
    val carrierId: UUID?,
    val cardsDistributorId: UUID?,
    val contractNumber: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val status: String,
    val commissionPercent: BigDecimal?,
    val createdAt: Instant,
    val updatedAt: Instant
)
