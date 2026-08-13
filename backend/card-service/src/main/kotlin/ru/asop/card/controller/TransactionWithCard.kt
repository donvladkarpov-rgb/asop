package ru.asop.card.controller

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TransactionWithCard(
    val transactionId: UUID,
    val sessionId: UUID?,
    val transactionTypeId: UUID,
    val transactionResultId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val metadata: String?,
    val startedAt: Instant,
    val completedAt: Instant?,
    val cardId: UUID?,
    val userId: UUID?
)
