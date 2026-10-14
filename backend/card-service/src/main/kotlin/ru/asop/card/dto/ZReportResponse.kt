package ru.asop.card.dto

import java.math.BigDecimal
import java.time.LocalDate

data class ZReportResponse(
    val title: String,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val totalRows: Long,
    val limit: Int,
    val offset: Int,
    val totals: ZReportTotals,
    val rows: List<ZReportRow>,
    val segments: List<ZReportSegment>
)

/**
 * Итоги по полному отфильтрованному набору смен (не зависят от limit/offset):
 * кол-во смен + агрегаты четырёх категорий оплат + суммарные кол-во/сумма «Билетов».
 */
data class ZReportTotals(
    val shiftsCount: Long,
    val cashCount: Long,
    val cashSum: BigDecimal,
    val bankCount: Long,
    val bankSum: BigDecimal,
    val mifareCount: Long,
    val mifareSum: BigDecimal,
    val benefitCount: Long,
    val benefitSum: BigDecimal
) {
    val totalCount: Long
        get() = cashCount + bankCount + mifareCount + benefitCount

    val totalSum: BigDecimal
        get() = cashSum.add(bankSum).add(mifareSum).add(benefitSum)
}
