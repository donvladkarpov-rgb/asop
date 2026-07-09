package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_TRANSACTION_RESULTS")
data class TransactionResultEntity(
    @Id
    val transactionResultId: UUID,
    val transactionResultName: String
)
