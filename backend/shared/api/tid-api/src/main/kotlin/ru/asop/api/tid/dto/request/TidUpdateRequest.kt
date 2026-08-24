package ru.asop.api.tid.dto.request

import jakarta.validation.constraints.Size
import java.util.UUID

data class TidUpdateRequest(
    val contractId: UUID? = null,
    @field:Size(max = 20, message = "TID value must be less than 20 characters")
    val tidValue: String? = null,
    val status: String? = null,
    val terminalId: UUID? = null
)
