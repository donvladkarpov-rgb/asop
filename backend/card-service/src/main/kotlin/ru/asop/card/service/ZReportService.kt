package ru.asop.card.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.card.dto.ZReportResponse
import ru.asop.card.repository.ZReportRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class ZReportService(
    private val repository: ZReportRepository
) {

    private val ru = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun build(
        filter: ZReportRepository.Filter,
        limit: Int,
        offset: Int
    ): Mono<ZReportResponse> {
        val totalRows = repository.count(filter)
        val rows = repository.findRows(filter, limit, offset).collectList()
        val segments = repository.findSegments(filter).collectList()
        val totals = repository.findTotals(filter)
        return Mono.zip(totalRows, rows, segments, totals).map { t ->
            ZReportResponse(
                title = "Z отчёт за период с ${filter.dateFrom.format(ru)} по ${filter.dateTo.format(ru)}",
                dateFrom = filter.dateFrom,
                dateTo = filter.dateTo,
                totalRows = t.t1,
                limit = limit,
                offset = offset,
                totals = t.t4,
                rows = t.t2,
                segments = t.t3
            )
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 1_000
    }
}
