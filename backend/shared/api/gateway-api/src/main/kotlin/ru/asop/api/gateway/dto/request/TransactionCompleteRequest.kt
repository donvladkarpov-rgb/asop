package ru.asop.api.gateway.dto.request

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

data class TransactionCompleteRequest(
    @field:NotNull
    val sessionId: UUID,

    @field:NotNull
    val transactionTypeId: UUID,

    @field:NotNull
    val transactionResultId: UUID,

    // Промпт 011 §8: валидация пассажира "без списания" — amount = 0.0.
    // Раньше @Positive отвергал 0 → пассажирский tap падал с 400.
    @field:NotNull
    @field:DecimalMin("0.0")
    val amount: BigDecimal,

    val currency: String = "RUB",

    val cardId: UUID? = null,

    val metadata: String? = null,

    val regionId: UUID? = null,

    val carrierId: UUID? = null,

    val timezone: String? = null
)
