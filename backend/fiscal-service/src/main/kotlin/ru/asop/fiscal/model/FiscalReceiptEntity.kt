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
    val carrierId: UUID? = null,
    val carrierFiscalizerId: UUID? = null,
    val receiptNumber: String? = null,
    val fiscalSign: String? = null,
    val status: String = "PENDING",
    val attemptCount: Int = 0,
    val maxAttempts: Int = 10,
    val lastErrorMessage: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
