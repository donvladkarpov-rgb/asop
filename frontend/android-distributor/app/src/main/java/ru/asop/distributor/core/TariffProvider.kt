package ru.asop.distributor.core

import ru.asop.distributor.sync.SyncStore

/**
 * Льготные тарифы (ASOP_TARIFF_RATES) для расчёта суммы пополнения.
 * Синхронизируются серверным контуром. defaultFare = цена дефолтной записи
 * (carrierId/zoneId/pathId все NULL); фолбэк — минимальная цена live-записей, затем 30.0.
 */
class TariffProvider(
    private val store: SyncStore
) {

    @Volatile
    private var defaultFareCache: Double? = null

    /** Стоимость одной поездки (руб.). */
    val defaultFare: Double
        get() = defaultFareCache ?: 30.0

    /** Поездок за сумму пополнения (округление вниз, минимум 1). */
    fun tripsFor(amount: Double): Int =
        (amount / defaultFare).toInt().coerceAtLeast(1)

    suspend fun refresh() {
        val live = store.tariffs().filter { it.deletedAt == null && it.isActive }
        val flat = live.filter { it.carrierId == null && it.zoneId == null && it.pathId == null }
        defaultFareCache = flat.minOfOrNull { it.price }
            ?: live.minOfOrNull { it.price }
            ?: 30.0
    }
}