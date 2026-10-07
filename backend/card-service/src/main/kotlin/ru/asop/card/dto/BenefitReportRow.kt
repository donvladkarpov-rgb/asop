package ru.asop.card.dto

import java.math.BigDecimal
import java.util.UUID

/**
 * Строка сводного отчёта по льготной категории (зерно: регион + категория).
 *
 * `tripsWithoutRate` — число поездок категории, для которых не удалось определить
 * ставку возмещения (нет активного тарифа или у категории не заданы шаги скидки):
 * такие поездки входят в `tripsCount`, но дают 0 к `compensation`.
 */
data class BenefitReportRow(
    val regionId: UUID?,
    val regionName: String?,
    val benefitId: UUID?,
    val benefitCode: String?,
    val benefitName: String?,
    val tripsCount: Long,
    val compensation: BigDecimal,
    val tripsWithoutRate: Long
)
