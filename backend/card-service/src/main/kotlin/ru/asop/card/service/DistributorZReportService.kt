package ru.asop.card.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.card.dto.DistributorZReportResponse
import ru.asop.card.repository.DistributorZReportRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class DistributorZReportService(
    private val repository: DistributorZReportRepository
) {

    private val ru = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun build(
        filter: DistributorZReportRepository.Filter,
        limit: Int,
        offset: Int
    ): Mono<DistributorZReportResponse> {
        val totalRows = repository.count(filter)
        val rows = repository.findRows(filter, limit, offset).collectList()
        val totals = repository.findTotals(filter)
        return Mono.zip(totalRows, rows, totals).map { t ->
            DistributorZReportResponse(
                title = "Z отчёт (дистрибьюторы) за период с ${filter.dateFrom.format(ru)} по ${filter.dateTo.format(ru)}",
                dateFrom = filter.dateFrom,
                dateTo = filter.dateTo,
                totalRows = t.t1,
                limit = limit,
                offset = offset,
                totals = t.t3,
                rows = t.t2
            )
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 1_000
    }
}
