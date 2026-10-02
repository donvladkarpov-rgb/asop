package ru.asop.card.dto

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Строка-итог по одному уровню группировки отчёта. */
data class TripRegistrationReportTotal(
    val level: Int,
    val levelName: String,
    val organizerId: UUID?,
    val organizerName: String?,
    val carrierId: UUID?,
    val carrierName: String?,
    val routeId: UUID?,
    val routeLabel: String?,
    val shiftId: UUID?,
    val tripId: UUID?,
    val tripsCount: Long,
    val amount: BigDecimal
) {
    companion object {
        const val LEVEL_ORGANIZER = 1
        const val LEVEL_CARRIER = 2
        const val LEVEL_ROUTE = 3
        const val LEVEL_SHIFT = 4
        const val LEVEL_TRIP = 5

        val LEVEL_NAMES = mapOf(
            LEVEL_ORGANIZER to "Организатор",
            LEVEL_CARRIER to "Перевозчик",
            LEVEL_ROUTE to "Маршрут",
            LEVEL_SHIFT to "Смена",
            LEVEL_TRIP to "Рейс"
        )
    }
}

data class TripRegistrationReportResponse(
    val title: String,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val totalRows: Long,
    val limit: Int,
    val offset: Int,
    val totals: List<TripRegistrationReportTotal>,
    val grandTotalAmount: BigDecimal,
    val grandTotalTrips: Long,
    val rows: List<TripRegistrationReportRow>
)