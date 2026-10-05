package ru.asop.card.dto

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class ShiftReportResponse(
    val title: String,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val totalRows: Long,
    val limit: Int,
    val offset: Int,
    val totals: List<ShiftReportTotal>,
    val grandTotalTransactions: Long,
    val grandTotalCashlessAmount: BigDecimal,
    val grandTotalCashAmount: BigDecimal,
    val rows: List<ShiftReportRow>
)
