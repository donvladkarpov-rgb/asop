package ru.asop.api.debt.dto.response

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class DebtResponse(
    val id: UUID,
    val cardId: UUID,
    val carrierId: UUID,
    val debtAmount: BigDecimal,
    val debtStatus: String,
    val debtOpenedAt: Instant,
    val debtDueDate: Instant,
    val createdAt: Instant,
    val updatedAt: Instant
)
