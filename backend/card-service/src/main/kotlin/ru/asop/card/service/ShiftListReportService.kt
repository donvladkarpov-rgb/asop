package ru.asop.card.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.card.dto.ShiftListReportResponse
import ru.asop.card.repository.ShiftListReportRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class ShiftListReportService(
    private val repository: ShiftListReportRepository
) {

    private val ru = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun build(
        filter: ShiftListReportRepository.Filter,
        limit: Int,
        offset: Int
    ): Mono<ShiftListReportResponse> {
        val totalRows = repository.count(filter)
        val rows = repository.findRows(filter, limit, offset).collectList()
        val totals = repository.findTotals(filter)
        return totalRows.zipWith(rows).zipWith(totals).map { outer ->
            ShiftListReportResponse(
                title = "Список смен за период с ${filter.dateFrom.format(ru)} по ${filter.dateTo.format(ru)}",
                dateFrom = filter.dateFrom,
                dateTo = filter.dateTo,
                totalRows = outer.t1.t1,
                limit = limit,
                offset = offset,
                totals = outer.t2,
                rows = outer.t1.t2
            )
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 1_000
    }
}
