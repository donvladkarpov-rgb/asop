package ru.asop.api.fiscal.dto.response

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class FiscalReceiptResponse(
    val id: UUID,
    val transactionId: UUID,
    val amount: BigDecimal,
    val status: String,
    val fiscalNumber: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
