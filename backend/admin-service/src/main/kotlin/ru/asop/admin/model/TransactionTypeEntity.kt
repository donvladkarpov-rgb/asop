package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_TRANSACTION_TYPES")
data class TransactionTypeEntity(
    @Id
    val transactionTypeId: UUID,
    val transactionTypeName: String
)
