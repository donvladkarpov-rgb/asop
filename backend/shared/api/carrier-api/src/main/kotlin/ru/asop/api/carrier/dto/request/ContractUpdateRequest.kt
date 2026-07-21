package ru.asop.api.carrier.dto.request

import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ContractUpdateRequest(
    val contractorType: String? = null,

    val carrierId: UUID? = null,

    val cardsDistributorId: UUID? = null,

    @field:Size(max = 100, message = "Contract number must be less than 100 characters")
    val contractNumber: String? = null,

    val startDate: LocalDate? = null,

    val endDate: LocalDate? = null,

    val status: String? = null,

    @field:PositiveOrZero
    val commissionPercent: BigDecimal? = null,

    val attributes: String? = null,

    val clearCarrierId: Boolean = false,

    val clearCardsDistributorId: Boolean = false
)
