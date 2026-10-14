package ru.asop.card.controller

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.card.dto.DistributorZReportResponse
import ru.asop.card.repository.DistributorZReportRepository
import ru.asop.card.service.DistributorZReportService
import java.time.LocalDate
import java.util.UUID

/**
 * Отчёт «Z отчёт (дистрибьюторы)» — реестр фискальных чеков дистрибьюторов карт.
 * Способ оплаты: наличные / банковская карта (по acqReference). Итоги — кол-во/сумма
 * по обеим категориям.
 *
 * Наружу только через gateway (sync-proxy под JWT web-admin).
 */
@RestController
@RequestMapping("/api/v1/reports")
class DistributorZReportController(
    private val service: DistributorZReportService
) {

    @GetMapping("/distributor-z-report")
    fun distributorZReport(
        @RequestParam("dateFrom") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateFrom: LocalDate,
        @RequestParam("dateTo") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateTo: LocalDate,
        @RequestParam("cardsDistributorId", required = false) cardsDistributorId: UUID?,
        @RequestParam("limit", required = false) limit: Int?,
        @RequestParam("offset", required = false) offset: Int?
    ): Mono<DistributorZReportResponse> {
        if (dateTo.isBefore(dateFrom)) {
            return Mono.error(IllegalArgumentException("dateTo must not be earlier than dateFrom"))
        }
        val filter = DistributorZReportRepository.Filter(
            dateFrom = dateFrom,
            dateTo = dateTo,
            cardsDistributorId = cardsDistributorId
        )
        return service.build(
            filter,
            limit?.coerceIn(1, 5_000) ?: DistributorZReportService.DEFAULT_LIMIT,
            (offset ?: 0).coerceAtLeast(0)
        )
    }
}
