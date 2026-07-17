package ru.asop.card.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("ASOP_TRANSACTIONS")
data class TransactionEntity(
    @Id
    val transactionId: UUID,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val sessionId: UUID? = null,
    val transactionTypeId: UUID,
    val transactionResultId: UUID,
    val amount: BigDecimal = BigDecimal.ZERO,
    val currency: String = "RUB",
    val acquirerReference: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val metadata: String? = null
)
