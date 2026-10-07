package ru.asop.card.dto

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Строка отчёта «Список смен» (25 колонок = 23 из внешнего CSV + ТК кол-во/сумма).
 *
 * Группы оплат (вселенная: типы …0801/…0803, результаты …0901/…0903, не declined):
 *  - БК  — эквайринг (metadata.bankCard ∨ ASOP_BANK_PAYMENTS);
 *  - МФК — льготные поездки (metadata.benefitId → fallback назначения льготы
 *          пользователю), сумма = компенсация (тариф × доля скидки);
 *  - ТК  — миферы без льгот (поездки списаны с карты), сумма = тариф × кол-во;
 *  - Нал — прочие оплаты проезда, сумма = metadata.amount ∨ AMOUNT.
 */
data class ShiftListRow(
    val carrierName: String?,
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
    val vehicleTypeName: String?,
    val routeNumber: String?,
    val routeName: String?,
    val organizerName: String?,
    val territoryNames: String?,
    val routeCategoryLabel: String?,
    val routeStartedAt: Instant?,
    val routeEndedAt: Instant?,
    val bkCount: Long,
    val bkSum: BigDecimal,
    val mfkCount: Long,
    val mfkSum: BigDecimal,
    val tkCount: Long,
    val tkSum: BigDecimal,
    val cashCount: Long,
    val cashSum: BigDecimal
) {
    val totalCount: Long
        get() = bkCount + mfkCount + tkCount + cashCount

    val totalSum: BigDecimal
        get() = bkSum.add(mfkSum).add(tkSum).add(cashSum)

    /** «ФИО водителя» — «Иванов И.И.» (тот же формат, что в отчёте-реестре, без даты рождения). */
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

        /** ROUTE_CATEGORY → подпись как в внешнем отчёте («городской», …); NULL → «—» на фронте. */
        fun routeCategoryLabel(category: String?): String? = when (category) {
            "CITY" -> "городской"
            "SUBURBAN" -> "пригородный"
            "INTERCITY" -> "межгородный"
            "EXPRESS" -> "экспресс"
            else -> null
        }
    }
}
