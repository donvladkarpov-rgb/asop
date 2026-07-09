package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.Size

data class TariffTypeUpdateRequest(
    @field:Size(max = 50)
    val code: String? = null,

    @field:Size(max = 100)
    val name: String? = null,

    @field:Size(max = 65535)
    val description: String? = null
)
