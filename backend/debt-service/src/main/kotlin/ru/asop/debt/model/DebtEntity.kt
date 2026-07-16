package ru.asop.debt.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("ASOP_CARD_DEBTS")
data class DebtEntity(
    @Id
    val debtId: UUID,
    val cardId: UUID,
    val carrierId: UUID,
    val debtAmount: BigDecimal,
    val debtStatus: String = "OPEN",
    val debtOpenedAt: Instant,
    val debtDueDate: Instant,
    val terminalId: UUID? = null,
    val sessionId: UUID? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
