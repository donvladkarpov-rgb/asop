package ru.asop.api.reference.dto.response

import java.util.UUID

data class TransactionResultResponse(
    val id: UUID,
    val transactionResultName: String
)
