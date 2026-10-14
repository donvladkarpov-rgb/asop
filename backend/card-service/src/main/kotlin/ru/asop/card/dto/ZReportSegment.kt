package ru.asop.card.dto

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Строка детализации Z-отчёта по маршрутам (уровень рейса/сегмента внутри смены).
 * Аналогична [ZReportRow], но на уровне одного рейса: маршрут + начало/окончание сегмента.
 */
data class ZReportSegment(
    val shiftId: UUID,
    val routeNumber: String?,
    val routeName: String?,
    val segmentStartedAt: Instant?,
    val segmentEndedAt: Instant?,
    val cashCount: Long,
    val cashSum: BigDecimal,
    val bankCount: Long,
    val bankSum: BigDecimal,
    val mifareCount: Long,
    val mifareSum: BigDecimal,
    val benefitCount: Long,
    val benefitSum: BigDecimal
) {
    val totalCount: Long
        get() = cashCount + bankCount + mifareCount + benefitCount

    val totalSum: BigDecimal
        get() = cashSum.add(bankSum).add(mifareSum).add(benefitSum)
}
