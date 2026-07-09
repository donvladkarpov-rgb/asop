package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class TransactionResultCreateRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val transactionResultName: String
)
