package ru.asop.api.debt.dto.request

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.util.UUID

data class DebtCreateRequest(
    @field:NotNull
    val cardId: UUID,

    @field:NotNull
    val carrierId: UUID,

    @field:NotNull
    @field:Positive
    val debtAmount: BigDecimal,

    val terminalId: UUID? = null,

    val sessionId: UUID? = null,

    val regionId: UUID? = null,

    val timezone: String? = null
)
