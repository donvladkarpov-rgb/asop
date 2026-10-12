package ru.asop.api.fiscal.dto

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class FiscalizerDto(
    val fiscalizerId: UUID,
    val fiscalizerCode: String,
    val fiscalizerName: String,
    val baseApiUrl: String?,
    val isActive: Boolean
)

data class CarrierFiscalizerDto(
    val carrierFiscalizerId: UUID,
    val carrierId: UUID,
    val fiscalizerId: UUID,
    val contractId: UUID,
    val isPrimary: Boolean,
    val isActive: Boolean,
    val customApiUrl: String?
)

data class DistributorFiscalizerDto(
    val distributorFiscalizerId: UUID,
    val cardsDistributorId: UUID,
    val fiscalizerId: UUID,
    val contractId: UUID,
    val isPrimary: Boolean,
    val isActive: Boolean,
    val customApiUrl: String?
)

data class FiscalReceiptStatusDto(
    val receiptId: UUID,
    val status: String,
    val fiscalUrl: String?,
    val receiptNumber: String?,
    val confirmedAt: OffsetDateTime?
)
