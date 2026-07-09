package ru.asop.api.carrier.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ContractCreateRequest(
    @field:NotBlank(message = "Contractor type is required")
    val contractorType: String,

    val carrierId: UUID? = null,

    val cardsDistributorId: UUID? = null,

    @field:NotBlank(message = "Contract number is required")
    @field:Size(max = 100, message = "Contract number must be less than 100 characters")
    val contractNumber: String,

    @field:NotNull(message = "Start date is required")
    val startDate: LocalDate,

    val endDate: LocalDate? = null,

    @field:PositiveOrZero
    val commissionPercent: BigDecimal? = null
)
