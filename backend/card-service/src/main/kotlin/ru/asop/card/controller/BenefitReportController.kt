package ru.asop.card.controller

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.card.dto.BenefitReportResponse
import ru.asop.card.repository.BenefitReportRepository
import ru.asop.card.service.BenefitReportService
import java.time.LocalDate
import java.util.UUID

/**
 * Сводный отчёт по льготным категориям («Сводный, льготники»): число льготных
 * поездок и сумма возмещения по категориям за период.
 *
 * Наружу только через gateway (sync-proxy под JWT web-admin).
 */
@RestController
@RequestMapping("/api/v1/reports")
class BenefitReportController(
    private val service: BenefitReportService
) {

    @GetMapping("/benefit-trips")
    fun benefitTrips(
        @RequestParam("dateFrom")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        dateFrom: LocalDate,
        @RequestParam("dateTo")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        dateTo: LocalDate,
        @RequestParam("regionId", required = false) regionId: UUID?,
        @RequestParam("carrierId", required = false) carrierId: UUID?,
        @RequestParam("benefitId", required = false) benefitId: UUID?,
        @RequestParam("limit", required = false) limit: Int?,
        @RequestParam("offset", required = false) offset: Int?
    ): Mono<BenefitReportResponse> {
        if (dateTo.isBefore(dateFrom)) {
            return Mono.error(IllegalArgumentException("dateTo must not be earlier than dateFrom"))
        }
        val filter = BenefitReportRepository.Filter(
            dateFrom = dateFrom,
            dateTo = dateTo,
            regionId = regionId,
            carrierId = carrierId,
            benefitId = benefitId
        )
        return service.build(
            filter,
            limit?.coerceIn(1, 5_000) ?: DEFAULT_LIMIT,
            (offset ?: 0).coerceAtLeast(0)
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 1_000
    }
}
