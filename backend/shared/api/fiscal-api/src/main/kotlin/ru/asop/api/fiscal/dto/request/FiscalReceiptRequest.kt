package ru.asop.api.fiscal.dto.request

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.util.UUID

data class FiscalReceiptRequest(
    @field:NotNull
    val transactionId: UUID,

    @field:NotNull
    @field:Positive
    val amount: BigDecimal,

    val description: String? = null
)
