package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class EventTypeCreateRequest(
    @field:NotBlank
    @field:Size(max = 4)
    val eventType: String,

    @field:NotBlank
    @field:Size(max = 128)
    val eventTypeName: String
)
