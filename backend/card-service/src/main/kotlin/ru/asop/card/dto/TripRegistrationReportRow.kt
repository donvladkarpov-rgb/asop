package ru.asop.card.dto

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Одна строка отчёта-реестра транзакций по операциям регистрации проезда.
 *
 * Поля `organizerId`/`carrierId`/`routeId`/`shiftId` в таблицу не выводятся —
 * они нужны фронту для группировки строк и построения строк-итогов.
 */
data class TripRegistrationReportRow(
    val organizerId: UUID?,
    val organizerName: String?,
    val carrierId: UUID?,
    val carrierName: String?,
    val routeId: UUID?,
    val routeNumber: String?,
    val routeName: String?,
    val shiftId: UUID?,
    val tripId: UUID,
    val tripSessionTypeId: UUID?,

    // 1..29 — колонки отчёта
    val vehicleTypeName: String?,
    val vehicleModelName: String?,
    val vehicleNumber: String?,
    val vehicleName: String?,
    val shiftStartedAt: Instant?,
    val terminalNumber: String?,
    val terminalSerial: String?,
    val driverLastName: String?,
    val driverFirstName: String?,
    val driverLastNameInitial: String?,
    val driverPatronymicInitial: String?,
    val driverBirthDate: LocalDate?,
    val cardNumber: String?,
    val transactionId: UUID,
    val tripAt: Instant,
    val processedAt: Instant,
    val status: String?,
    val passengerCategory: String?,
    val paymentForm: String?,
    val serviceName: String?,
    val tariffTypeName: String?,
    val appliedTariff: String?,
    val regulatedTariff: String?,
    val benefitShare: BigDecimal?,
    val benefitTripsAfter: Int?,
    val amount: BigDecimal,
    val originStopName: String?,
    val destinationStopName: String?,
    val fiscalStatus: String?,
    val fiscalCreatedAt: Instant?,
    val fiscalConfirmedAt: Instant?,
    val rrn: String?
) {
    /** «ФИО водителя» — «Иванов И.И.», с датой рождения в скобках, если заведена. */
    val driverName: String
        get() {
            val base = listOfNotNull(
                driverLastName?.takeIf { it.isNotBlank() },
                driverFirstName?.takeIf { it.isNotBlank() },
                listOfNotNull(
                    driverLastNameInitial?.takeIf { it.isNotBlank() },
                    driverPatronymicInitial?.takeIf { it.isNotBlank() }
                ).joinToString("").takeIf { it.isNotBlank() }
            ).joinToString(" ")
            if (base.isBlank()) return ""
            val birth = driverBirthDate
            return if (birth == null) base else "$base (${birth.dayOfMonth.toString().padStart(2, '0')}.${birth.monthValue.toString().padStart(2, '0')}.${birth.year})"
        }
}