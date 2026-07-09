package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class TariffTypeCreateRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val code: String,

    @field:NotBlank
    @field:Size(max = 100)
    val name: String,

    @field:Size(max = 65535)
    val description: String? = null
)
