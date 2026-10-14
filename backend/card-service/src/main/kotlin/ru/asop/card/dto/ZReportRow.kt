package ru.asop.card.dto

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Строка отчёта «Z отчёт» (уровень смены) — кассовый Z-отчёт терминала.
 *
 * Категории оплат (аналог групп БК/МФК/ТК/Нал из «Списка смен», но с семантикой Z-отчёта):
 *  - Наличные (`cash*`)     — наличная оплата (bucket CASH);
 *  - Банковская карта (`bank*`) — эквайринг (metadata.bankCard ∨ ASOP_BANK_PAYMENTS);
 *  - МФК (`mifare*`)        — МайФер Карта, поездки списаны с карты (bucket TK/ride_like);
 *  - Льгота (`benefit*`)  — любая льгота с гос. возмещением (bucket MFK/benefit_id).
 * Итог «Билетов» (`total*`) — сумма четырёх категорий.
 */
data class ZReportRow(
    val carrierName: String?,
    val carrierInn: String?,
    val shiftId: UUID,
    val driverLastName: String?,
    val driverFirstName: String?,
    val driverLastNameInitial: String?,
    val driverPatronymicInitial: String?,
    val shiftStartedAt: Instant?,
    val shiftClosedAt: Instant?,
    val durationText: String?,
    val terminalSerial: String?,
    val vehicleNumber: String?,
    val routeNumber: String?,
    val routeName: String?,
    val organizerName: String?,
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

    /** «ФИО водителя» — «Иванов И.И.» (тот же формат, что в остальных отчётах). */
    val driverName: String
        get() = listOfNotNull(
            driverLastName?.takeIf { it.isNotBlank() },
            driverFirstName?.takeIf { it.isNotBlank() },
            listOfNotNull(
                driverLastNameInitial?.takeIf { it.isNotBlank() },
                driverPatronymicInitial?.takeIf { it.isNotBlank() }
            ).joinToString("").takeIf { it.isNotBlank() }
        ).joinToString(" ")

    companion object {
        /** Длительность смены вида HH:MM:SS; null для открытых смен. */
        fun durationText(start: Instant?, end: Instant?): String? {
            if (start == null || end == null) return null
            val d = Duration.between(start, end)
            if (d.isNegative) return null
            return "%02d:%02d:%02d".format(d.toHours(), d.toMinutesPart(), d.toSecondsPart())
        }
    }
}
