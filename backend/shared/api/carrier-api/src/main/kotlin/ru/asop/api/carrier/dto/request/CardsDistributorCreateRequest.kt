package ru.asop.api.carrier.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class CardsDistributorCreateRequest(
    @field:NotBlank(message = "Distributor name is required")
    @field:Size(max = 255, message = "Distributor name must be less than 255 characters")
    val distributorName: String,

    @field:NotBlank(message = "INN is required")
    @field:Pattern(regexp = "^\\d{10}$|^\\d{12}$", message = "INN must be 10 or 12 digits")
    val inn: String,

    @field:Size(max = 9, message = "KPP must be less than 9 characters")
    val kpp: String? = null,

    @field:Size(max = 500, message = "Legal address must be less than 500 characters")
    val legalAddress: String? = null,

    @field:Size(max = 20, message = "Contact phone must be less than 20 characters")
    val contactPhone: String? = null,

    @field:Size(max = 100, message = "Contact email must be less than 100 characters")
    val contactEmail: String? = null,

    val isActive: Boolean = true
)
