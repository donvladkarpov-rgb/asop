package ru.asop.api.reference.dto.response

import java.util.UUID

data class TransactionTypeResponse(
    val id: UUID,
    val transactionTypeName: String
)
