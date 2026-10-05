package ru.asop.card.dto

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class ShiftReportRow(
    val organizerId: UUID?,
    val organizerName: String?,
    val carrierId: UUID?,
    val carrierName: String?,
    val routeId: UUID?,
    val routeNumber: String?,
    val routeName: String?,
    val shiftId: UUID,
    val vehicleTypeName: String?,
    val vehicleModelName: String?,
    val vehicleNumber: String?,
    val vehicleName: String?,
    val terminalSerial: String?,
    val terminalNumber: String?,
    val shiftStartedAt: Instant?,
    val shiftClosedAt: Instant?,
    val transactionsCount: Long,
    val successfulCardTransactions: Long,
    val failedCardTransactions: Long,
    val failedSharePct: BigDecimal,
    val cashlessAmount: BigDecimal,
    val cashlessAmountWithoutDiscount: BigDecimal,
    val cashlessCount: Long,
    val cashlessSharePct: BigDecimal,
    val cashAmount: BigDecimal,
    val cashCount: Long
)
