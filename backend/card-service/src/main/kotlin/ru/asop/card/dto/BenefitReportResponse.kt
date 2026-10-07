package ru.asop.card.dto

import java.math.BigDecimal
import java.time.LocalDate

data class BenefitReportResponse(
    val title: String,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val totalRows: Long,
    val limit: Int,
    val offset: Int,
    val totals: List<BenefitReportTotal>,
    val grandTotalTrips: Long,
    val grandTotalCompensation: BigDecimal,
    val grandTripsWithoutRate: Long,
    val rows: List<BenefitReportRow>
)
