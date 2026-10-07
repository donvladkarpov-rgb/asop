package ru.asop.card.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.card.dto.BenefitReportResponse
import ru.asop.card.dto.BenefitReportRow
import ru.asop.card.dto.BenefitReportTotal
import ru.asop.card.repository.BenefitReportRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class BenefitReportService(
    private val repository: BenefitReportRepository
) {

    private val ru = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun build(
        filter: BenefitReportRepository.Filter,
        limit: Int,
        offset: Int
    ): Mono<BenefitReportResponse> {
        val totalRows: Mono<Long> = repository.count(filter)
        val rows: Mono<List<BenefitReportRow>> =
            repository.findRows(filter, limit, offset).collectList()
        val totals: Mono<List<BenefitReportTotal>> =
            repository.findTotals(filter).collectList()
        return totalRows
            .zipWith(rows)
            .zipWith(totals)
            .map { outer ->
                buildResponse(filter, limit, offset, outer.t1.t1, outer.t1.t2, outer.t2)
            }
    }

    private fun buildResponse(
        filter: BenefitReportRepository.Filter,
        limit: Int,
        offset: Int,
        count: Long,
        rows: List<BenefitReportRow>,
        totals: List<BenefitReportTotal>
    ): BenefitReportResponse {
        // Итоги из GROUPING SETS считаются по всему отфильтрованному множеству, а строки —
        // только по текущей странице. Если строки не обрезаны, берём сумму строк, иначе —
        // агрегаты 1-го уровня (регион) — они всегда равны полной сумме.
        val wholeSet = offset == 0 && count == rows.size.toLong()
        val regionTotals = totals.filter { it.level == BenefitReportTotal.LEVEL_REGION }

        val trips = if (wholeSet) {
            rows.fold(0L) { acc, r -> acc + r.tripsCount }
        } else {
            regionTotals.fold(0L) { acc, t -> acc + t.tripsCount }
        }
        val compensation = if (wholeSet) {
            rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.compensation }
        } else {
            regionTotals.fold(BigDecimal.ZERO) { acc, t -> acc + t.compensation }
        }
        val withoutRate = if (wholeSet) {
            rows.fold(0L) { acc, r -> acc + r.tripsWithoutRate }
        } else {
            regionTotals.fold(0L) { acc, t -> acc + t.tripsWithoutRate }
        }

        return BenefitReportResponse(
            title = title(filter.dateFrom, filter.dateTo),
            dateFrom = filter.dateFrom,
            dateTo = filter.dateTo,
            totalRows = count,
            limit = limit,
            offset = offset,
            totals = totals.sortedWith(totalsComparator()),
            grandTotalTrips = trips,
            grandTotalCompensation = compensation,
            grandTripsWithoutRate = withoutRate,
            rows = rows
        )
    }

    private fun title(dateFrom: LocalDate, dateTo: LocalDate): String {
        val from = dateFrom.format(ru)
        val to = dateTo.format(ru)
        return "Сводный отчет о проданных билетах и поездках льготной категории граждан " +
            "(обработка: с $from по $to)"
    }

    /** Регион → категория. */
    private fun totalsComparator() = compareBy<BenefitReportTotal>(
        { it.level },
        { it.regionName ?: "" },
        { it.benefitCode ?: "" },
        { it.benefitName ?: "" }
    )
}
