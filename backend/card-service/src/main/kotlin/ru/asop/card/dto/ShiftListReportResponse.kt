package ru.asop.card.dto

import java.math.BigDecimal
import java.time.LocalDate

data class ShiftListReportResponse(
    val title: String,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val totalRows: Long,
    val limit: Int,
    val offset: Int,
    val totals: ShiftListTotals,
    val rows: List<ShiftListRow>
)

/**
 * Итоги по всем строкам выборки (не зависят от limit/offset): кол-во смен
 * и агрегаты четырёх групп оплат + суммарные кол-во/сумма.
 */
data class ShiftListTotals(
    val shiftsCount: Long,
    val bkCount: Long,
    val bkSum: BigDecimal,
    val mfkCount: Long,
    val mfkSum: BigDecimal,
    val tkCount: Long,
    val tkSum: BigDecimal,
    val cashCount: Long,
    val cashSum: BigDecimal
) {
    val totalCount: Long
        get() = bkCount + mfkCount + tkCount + cashCount

    val totalSum: BigDecimal
        get() = bkSum.add(mfkSum).add(tkSum).add(cashSum)
}
