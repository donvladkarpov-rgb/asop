package ru.asop.card.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("ASOP_TRANSACTION_CARDS")
data class TransactionCardEntity(
    @Id
    val transactionCardId: UUID,
    val transactionId: UUID,
    val cardId: UUID,
    val cardRole: String = "PAYER",
    val tariffAppliedId: UUID? = null,
    val balanceBefore: java.math.BigDecimal? = null,
    val balanceAfter: java.math.BigDecimal? = null
)
