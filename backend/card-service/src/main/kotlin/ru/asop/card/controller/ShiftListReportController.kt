package ru.asop.card.controller

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.card.dto.ShiftListReportResponse
import ru.asop.card.repository.ShiftListReportRepository
import ru.asop.card.service.ShiftListReportService
import java.time.LocalDate
import java.util.UUID

/**
 * Отчёт «Список смен»: строка на смену с разбивкой оплат на группы
 * БК/МФК/ТК/Нал (25 колонок — см. исходный CSV внешней системы).
 *
 * Наружу только через gateway (sync-proxy под JWT web-admin).
 */
@RestController
@RequestMapping("/api/v1/reports")
class ShiftListReportController(
    private val service: ShiftListReportService
) {

    @GetMapping("/shift-list")
    fun shiftList(
        @RequestParam("dateFrom") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateFrom: LocalDate,
        @RequestParam("dateTo") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateTo: LocalDate,
        @RequestParam("regionId", required = false) regionId: UUID?,
        @RequestParam("organizerId", required = false) organizerId: UUID?,
        @RequestParam("carrierId", required = false) carrierId: UUID?,
        @RequestParam("routeId", required = false) routeId: UUID?,
        @RequestParam("pathId", required = false) pathId: UUID?,
        @RequestParam("vehicleId", required = false) vehicleId: UUID?,
        @RequestParam("terminalId", required = false) terminalId: UUID?,
        @RequestParam("driverId", required = false) driverId: UUID?,
        @RequestParam("limit", required = false) limit: Int?,
        @RequestParam("offset", required = false) offset: Int?
    ): Mono<ShiftListReportResponse> {
        if (dateTo.isBefore(dateFrom)) {
            return Mono.error(IllegalArgumentException("dateTo must not be earlier than dateFrom"))
        }
        val filter = ShiftListReportRepository.Filter(
            dateFrom = dateFrom,
            dateTo = dateTo,
            regionId = regionId,
            organizerId = organizerId,
            carrierId = carrierId,
            routeId = routeId,
            pathId = pathId,
            vehicleId = vehicleId,
            terminalId = terminalId,
            driverId = driverId
        )
        return service.build(
            filter,
            limit?.coerceIn(1, 5_000) ?: ShiftListReportService.DEFAULT_LIMIT,
            (offset ?: 0).coerceAtLeast(0)
        )
    }
}
