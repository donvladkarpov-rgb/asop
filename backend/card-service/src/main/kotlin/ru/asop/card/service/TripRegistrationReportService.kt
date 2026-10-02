package ru.asop.card.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.card.dto.TripRegistrationReportResponse
import ru.asop.card.dto.TripRegistrationReportRow
import ru.asop.card.dto.TripRegistrationReportTotal
import ru.asop.card.repository.TripRegistrationReportRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class TripRegistrationReportService(
    private val repository: TripRegistrationReportRepository
) {

    private val ru = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun build(
        filter: TripRegistrationReportRepository.Filter,
        limit: Int,
        offset: Int
    ): Mono<TripRegistrationReportResponse> {
        val totalRows: Mono<Long> = repository.count(filter)
        val rows: Mono<List<TripRegistrationReportRow>> =
            repository.findRows(filter, limit, offset).collectList()
        val totals: Mono<List<TripRegistrationReportTotal>> =
            repository.findTotals(filter).collectList()
        return totalRows
            .zipWith(rows)
            .zipWith(totals)
            .map { outer ->
                buildResponse(filter, limit, offset, outer.t1.t1, outer.t1.t2, outer.t2)
            }
    }

    private fun buildResponse(
        filter: TripRegistrationReportRepository.Filter,
        limit: Int,
        offset: Int,
        count: Long,
        rows: List<TripRegistrationReportRow>,
        totals: List<TripRegistrationReportTotal>
    ): TripRegistrationReportResponse {
        // Итоги из GROUPING SETS считаются по всему отфильтрованному множеству, а строки —
        // только по текущей странице. Если строки не обрезаны, берём сумму строк, иначе —
        // агрегат 1-го уровня (он всегда равен полной сумме).
        val wholeSet = offset == 0 && count == rows.size.toLong()
        val amountByRows = rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.amount }
        val organizerTotals = totals.filter { it.level == TripRegistrationReportTotal.LEVEL_ORGANIZER }
        val amountByTotals = organizerTotals.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }

        return TripRegistrationReportResponse(
            title = title(filter.dateFrom, filter.dateTo),
            dateFrom = filter.dateFrom,
            dateTo = filter.dateTo,
            totalRows = count,
            limit = limit,
            offset = offset,
            totals = totals.sortedWith(totalsComparator()),
            grandTotalAmount = if (wholeSet) amountByRows else amountByTotals,
            grandTotalTrips = if (wholeSet) count else organizerTotals.sumOf { it.tripsCount },
            rows = rows
        )
    }

    private fun title(dateFrom: LocalDate, dateTo: LocalDate): String {
        val from = dateFrom.format(ru)
        val to = dateTo.format(ru)
        return "Отчет-реестр транзакций по операциям регистрации проезда за период " +
            "(обработка: с $from по $to, на терминале: с $from по $to)"
    }

    /** Организатор → перевозчик → маршрут → смена → рейс. */
    private fun totalsComparator() = compareBy<TripRegistrationReportTotal>(
        { it.level },
        { it.organizerName ?: "" },
        { it.carrierName ?: "" },
        { it.routeLabel ?: "" },
        { it.shiftId?.toString() ?: "" },
        { it.tripId?.toString() ?: "" }
    )

    companion object {
        val DEFAULT_TYPE_IDS = TripRegistrationReportRepository.DEFAULT_TRANSACTION_TYPE_IDS
    }
}