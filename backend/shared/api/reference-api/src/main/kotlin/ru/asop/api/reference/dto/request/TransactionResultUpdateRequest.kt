package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.Size

data class TransactionResultUpdateRequest(
    @field:Size(max = 255)
    val transactionResultName: String? = null
)
