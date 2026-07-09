package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SessionTypeCreateRequest(
    @field:NotBlank
    @field:Size(max = 30)
    val sessionTypeCode: String,

    @field:NotBlank
    @field:Size(max = 100)
    val sessionTypeName: String
)
