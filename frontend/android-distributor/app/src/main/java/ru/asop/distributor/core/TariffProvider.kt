package ru.asop.distributor.core

/**
 * Льготные тарифы (tariff-rates) для расчёта суммы пополнения.
 *
 * TODO(Phase 4): заменить на серверный контур (pull `asop_tariff_rates`) до начисления.
 */
class TariffProvider {

    /** Стоимость одной поездки (руб.), по умолчанию для MVP/scaffold. */
    val defaultFare: Double = 30.0

    /** Поездок за сумму пополнения (округление вниз, минимум 1). */
    fun tripsFor(amount: Double): Int =
        (amount / defaultFare).toInt().coerceAtLeast(1)
}