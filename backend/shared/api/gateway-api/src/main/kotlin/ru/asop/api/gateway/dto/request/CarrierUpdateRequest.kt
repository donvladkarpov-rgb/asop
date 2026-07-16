package ru.asop.api.gateway.dto.request

import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Запрос на обновление перевозчика.
 * Все поля опциональны — обновляются только указанные.
 */
data class CarrierUpdateRequest(
    @field:Size(max = 255, message = "Carrier name must be less than 255 characters")
    val carrierName: String? = null,

    @field:Pattern(regexp = "^\\d{10}$|^\\d{12}$", message = "INN must be 10 or 12 digits")
    val inn: String? = null,

    val regionId: UUID? = null
)