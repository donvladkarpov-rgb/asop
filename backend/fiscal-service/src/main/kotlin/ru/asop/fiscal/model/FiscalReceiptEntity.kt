package ru.asop.fiscal.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("ASOP_FISCAL_RECEIPTS")
data class FiscalReceiptEntity(
    @Id
    val receiptId: UUID,
    val transactionId: UUID,
    val amount: BigDecimal,
    val status: String = "PENDING",
    val fiscalNumber: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
