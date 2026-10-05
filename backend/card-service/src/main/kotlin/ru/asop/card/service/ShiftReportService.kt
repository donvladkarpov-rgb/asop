package ru.asop.card.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.card.dto.ShiftReportResponse
import ru.asop.card.dto.ShiftReportRow
import ru.asop.card.dto.ShiftReportTotal
import ru.asop.card.repository.ShiftReportRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class ShiftReportService(
    private val repository: ShiftReportRepository
) {

    private val ru = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun build(
        filter: ShiftReportRepository.Filter,
        limit: Int,
        offset: Int
    ): Mono<ShiftReportResponse> {
        val totalRows = repository.count(filter)
        val rows = repository.findRows(filter, limit, offset).collectList()
        val totals = repository.findTotals(filter).collectList()
        return totalRows.zipWith(rows).zipWith(totals).map { outer ->
            val count = outer.t1.t1
            val r = outer.t1.t2
            val t = outer.t2
            val wholeSet = offset == 0 && count == r.size.toLong()
            val organizerTotals = t.filter { it.level == ShiftReportTotal.LEVEL_ORGANIZER }
            ShiftReportResponse(
                title = "Отчет по сменам за период с ${filter.dateFrom.format(ru)} по ${filter.dateTo.format(ru)}",
                dateFrom = filter.dateFrom,
                dateTo = filter.dateTo,
                totalRows = count,
                limit = limit,
                offset = offset,
                totals = t.sortedWith(totalsComparator()),
                grandTotalTransactions = if (wholeSet) r.sumOf { it.transactionsCount } else organizerTotals.sumOf { it.transactionsCount },
                grandTotalCashlessAmount = if (wholeSet) r.fold(BigDecimal.ZERO) { acc, it -> acc + it.cashlessAmount } else organizerTotals.fold(BigDecimal.ZERO) { acc, it -> acc + it.cashlessAmount },
                grandTotalCashAmount = if (wholeSet) r.fold(BigDecimal.ZERO) { acc, it -> acc + it.cashAmount } else organizerTotals.fold(BigDecimal.ZERO) { acc, it -> acc + it.cashAmount },
                rows = r
            )
        }
    }

    private fun totalsComparator() = compareBy<ShiftReportTotal>(
        { it.level },
        { it.organizerName ?: "" },
        { it.carrierName ?: "" },
        { it.routeLabel ?: "" },
        { it.shiftId?.toString() ?: "" }
    )
}
