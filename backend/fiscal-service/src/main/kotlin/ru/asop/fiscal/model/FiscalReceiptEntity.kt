package ru.asop.fiscal.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("ASOP_FISCAL_RECEIPTS")
data class FiscalReceiptEntity(
    @Id
    val receiptId: UUID,
    val transactionId: UUID,
    val carrierId: UUID? = null,
    val cardsDistributorId: UUID? = null,
    val carrierFiscalizerId: UUID? = null,
    val distributorFiscalizerId: UUID? = null,
    val source: String = "TERMINAL",
    val paymentType: String = "CARD",
    val receiptNumber: String? = null,
    val fiscalSign: String? = null,
    val status: String = "PENDING",
    val attemptCount: Int = 0,
    val maxAttempts: Int = 10,
    val lastAttemptAt: OffsetDateTime? = null,
    val nextRetryAt: OffsetDateTime? = null,
    val lastErrorMessage: String? = null,
    val receiptData: String? = null,
    val requestPayload: String? = null,
    val responseData: String? = null,
    val fiscalUrl: String? = null,
    val confirmedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)
