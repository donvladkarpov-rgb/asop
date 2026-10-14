package ru.asop.fiscal.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import io.r2dbc.postgresql.codec.Json
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Table("asop_fiscalizers")
data class Fiscalizer(
    @Id val fiscalizerId: UUID,
    val fiscalizerCode: String,
    val fiscalizerName: String,
    val baseApiUrl: String? = null,
    val description: String? = null,
    val isActive: Boolean = true,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

@Table("asop_carrier_fiscalizers")
data class CarrierFiscalizer(
    @Id val carrierFiscalizerId: UUID,
    val carrierId: UUID,
    val fiscalizerId: UUID,
    val contractId: UUID,
    val customApiUrl: String? = null,
    val isPrimary: Boolean = false,
    val isActive: Boolean = true,
    val settings: String? = null,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

@Table("asop_distributor_fiscalizers")
data class DistributorFiscalizer(
    @Id val distributorFiscalizerId: UUID,
    val cardsDistributorId: UUID,
    val fiscalizerId: UUID,
    val contractId: UUID,
    val customApiUrl: String? = null,
    val isPrimary: Boolean = false,
    val isActive: Boolean = true,
    val settings: String? = null,
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

@Table("asop_fiscalizer_tokens")
data class FiscalizerToken(
    @Id val tokenId: UUID,
    val carrierFiscalizerId: UUID? = null,
    val distributorFiscalizerId: UUID? = null,
    val tokenEncrypted: ByteArray = ByteArray(0),
    val tokenHint: String? = null,
    val validFrom: java.time.OffsetDateTime = OffsetDateTime.now(),
    val validUntil: java.time.OffsetDateTime = OffsetDateTime.now().plusYears(1),
    val status: String = "ACTIVE",
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

@Table("asop_fiscal_receipt_attempts")
data class FiscalReceiptAttempt(
    @Id val attemptId: UUID,
    val receiptId: UUID,
    val attemptNumber: Int = 1,
    val tokenId: UUID? = null,
    val requestPayload: Json? = null,
    val responsePayload: Json? = null,
    val httpStatus: Int? = null,
    val success: Boolean = false,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val attemptedAt: java.time.OffsetDateTime = OffsetDateTime.now(),
    val durationMs: Int? = null
)
