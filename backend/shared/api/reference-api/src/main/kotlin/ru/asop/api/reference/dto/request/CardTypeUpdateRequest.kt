package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.Size

data class CardTypeUpdateRequest(
    @field:Size(max = 255)
    val cardTypeName: String? = null
)
