package ru.asop.fiscal.model

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

@Table("ASOP_FISCAL_RECEIPTS")
data class FiscalReceiptEntity(
    @Id
    val receiptId: UUID,
    val transactionId: UUID? = null,
    val acqReference: String? = null,
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
    // JSONB-колонки — тип io.r2dbc.postgresql.codec.Json (иначе String биндится как varchar
    // и INSERT/UPDATE падает: «column ... is of type jsonb but expression is of type character varying»).
    val receiptData: Json? = null,
    val requestPayload: Json? = null,
    val responseData: Json? = null,
    val fiscalUrl: String? = null,
    val confirmedAt: OffsetDateTime? = null,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)
