package ru.asop.api.tid.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.UUID

data class TidCreateRequest(
    @field:NotNull(message = "Carrier ID is required")
    val carrierId: UUID,

    @field:NotBlank(message = "TID value is required")
    @field:Size(max = 20, message = "TID value must be less than 20 characters")
    val tidValue: String
)
