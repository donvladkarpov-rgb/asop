package ru.asop.card.dto

import java.math.BigDecimal
import java.util.UUID

data class ShiftReportTotal(
    val level: Int,
    val levelName: String,
    val organizerId: UUID?,
    val organizerName: String?,
    val carrierId: UUID?,
    val carrierName: String?,
    val routeId: UUID?,
    val routeLabel: String?,
    val shiftId: UUID?,
    val shiftsCount: Long,
    val transactionsCount: Long,
    val cashlessAmount: BigDecimal,
    val cashAmount: BigDecimal
) {
    companion object {
        const val LEVEL_ORGANIZER = 1
        const val LEVEL_CARRIER = 2
        const val LEVEL_ROUTE = 3
        const val LEVEL_SHIFT = 4

        val LEVEL_NAMES = mapOf(
            LEVEL_ORGANIZER to "Организатор",
            LEVEL_CARRIER to "Перевозчик",
            LEVEL_ROUTE to "Маршрут",
            LEVEL_SHIFT to "Смена"
        )
    }
}
