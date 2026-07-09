package ru.asop.api.gateway.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * Запрос на создание перевозчика.
 * Валидация:
 * - carrierName: не пустое, до 255 символов
 * - inn: 10 или 12 цифр (контрольная сумма проверяется в сервисе через InnValidator)
 * - regionId: не null, должен существовать в БД (проверяется в сервисе)
 */
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