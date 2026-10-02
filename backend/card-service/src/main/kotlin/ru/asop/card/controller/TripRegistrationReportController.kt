package ru.asop.card.controller

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.card.dto.TripRegistrationReportResponse
import ru.asop.card.repository.TripRegistrationReportRepository
import ru.asop.card.service.TripRegistrationReportService
import java.time.LocalDate
import java.util.UUID

/**
 * Отчёт-реестр транзакций по операциям регистрации проезда.
 *
 * Открыт наружу только через gateway (sync-proxy под JWT web-admin) — сам card-service
 * доверяет внутренней сети, см. SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/reports")
class TripRegistrationReportController(
    private val service: TripRegistrationReportService
) {

    @GetMapping("/trip-registrations")
    fun tripRegistrations(
        @RequestParam("dateFrom")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        dateFrom: LocalDate,
        @RequestParam("dateTo")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        dateTo: LocalDate,
        @RequestParam("regionId", required = false) regionId: UUID?,
        @RequestParam("organizerId", required = false) organizerId: UUID?,
        @RequestParam("carrierId", required = false) carrierId: UUID?,
        @RequestParam("routeId", required = false) routeId: UUID?,
        @RequestParam("pathId", required = false) pathId: UUID?,
        @RequestParam("vehicleId", required = false) vehicleId: UUID?,
        @RequestParam("terminalId", required = false) terminalId: UUID?,
        @RequestParam("driverId", required = false) driverId: UUID?,
        @RequestParam("transactionTypeIds", required = false) transactionTypeIds: String?,
        @RequestParam("limit", required = false) limit: Int?,
        @RequestParam("offset", required = false) offset: Int?
    ): Mono<TripRegistrationReportResponse> {
        if (dateTo.isBefore(dateFrom)) {
            return Mono.error(IllegalArgumentException("dateTo must not be earlier than dateFrom"))
        }
        val filter = TripRegistrationReportRepository.Filter(
            dateFrom = dateFrom,
            dateTo = dateTo,
            regionId = regionId,
            organizerId = organizerId,
            carrierId = carrierId,
            routeId = routeId,
            pathId = pathId,
            vehicleId = vehicleId,
            terminalId = terminalId,
            driverId = driverId,
            transactionTypeIds = parseTypes(transactionTypeIds)
        )
        return service.build(
            filter,
            limit?.coerceIn(1, 5_000) ?: DEFAULT_LIMIT,
            (offset ?: 0).coerceAtLeast(0)
        )
    }

    /** Пусто → дефолтные типы (оплата проезда + валидация без списания). */
    private fun parseTypes(raw: String?): List<UUID> {
        val parsed = raw?.split(",")
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let { v -> runCatching { UUID.fromString(v) }.getOrNull() } }
            ?.filter { it != UUID(0L, 0L) }
            .orEmpty()
        return parsed.ifEmpty { TripRegistrationReportService.DEFAULT_TYPE_IDS }
    }

    companion object {
        const val DEFAULT_LIMIT = 1_000
    }
}