package ru.asop.api.carrier.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

data class CarrierCreateRequest(
    @field:NotBlank(message = "Carrier name is required")
    @field:Size(max = 255, message = "Carrier name must be less than 255 characters")
    val carrierName: String,

    @field:NotBlank(message = "INN is required")
    @field:Pattern(regexp = "^\\d{10}$|^\\d{12}$", message = "INN must be 10 or 12 digits")
    val inn: String,

    @field:NotNull(message = "Region ID is required")
    val regionId: UUID
)
