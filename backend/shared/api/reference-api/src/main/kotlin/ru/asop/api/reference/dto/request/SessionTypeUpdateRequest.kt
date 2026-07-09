package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.Size

data class SessionTypeUpdateRequest(
    @field:Size(max = 30)
    val sessionTypeCode: String? = null,

    @field:Size(max = 100)
    val sessionTypeName: String? = null
)
