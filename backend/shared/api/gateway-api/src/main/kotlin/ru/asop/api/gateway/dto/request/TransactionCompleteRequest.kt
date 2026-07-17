package ru.asop.api.gateway.dto.request

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.util.UUID

data class TransactionCompleteRequest(
    @field:NotNull
    val sessionId: UUID,

    @field:NotNull
    val transactionTypeId: UUID,

    @field:NotNull
    val transactionResultId: UUID,

    @field:NotNull
    @field:Positive
    val amount: BigDecimal,

    val currency: String = "RUB",

    val cardId: UUID? = null,

    val metadata: String? = null
)
