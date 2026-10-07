package ru.asop.card.dto

import java.math.BigDecimal
import java.util.UUID

/** Итог сводного отчёта по льготам: 1 уровень — регион, 2 — регион + категория. */
data class BenefitReportTotal(
    val level: Int,
    val levelName: String,
    val regionId: UUID?,
    val regionName: String?,
    val benefitId: UUID?,
    val benefitCode: String?,
    val benefitName: String?,
    val tripsCount: Long,
    val compensation: BigDecimal,
    val tripsWithoutRate: Long
) {
    companion object {
        const val LEVEL_REGION = 1
        const val LEVEL_BENEFIT = 2

        val LEVEL_NAMES = mapOf(
            LEVEL_REGION to "Регион",
            LEVEL_BENEFIT to "Категория"
        )
    }
}
