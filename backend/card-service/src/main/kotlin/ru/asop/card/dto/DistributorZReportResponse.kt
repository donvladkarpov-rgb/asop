package ru.asop.card.dto

import java.math.BigDecimal
import java.time.LocalDate

data class DistributorZReportResponse(
    val title: String,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val totalRows: Long,
    val limit: Int,
    val offset: Int,
    val totals: DistributorZReportTotals,
    val rows: List<DistributorZReportRow>
)

/**
 * Итоги по полному отфильтрованному набору чеков (не зависят от limit/offset):
 * кол-во чеков + разбивка суммы по способу оплаты (наличные / банковская карта).
 */
data class DistributorZReportTotals(
    val receiptsCount: Long,
    val cashCount: Long,
    val cashSum: BigDecimal,
    val bankCount: Long,
    val bankSum: BigDecimal
) {
    val totalSum: BigDecimal
        get() = cashSum.add(bankSum)
}
