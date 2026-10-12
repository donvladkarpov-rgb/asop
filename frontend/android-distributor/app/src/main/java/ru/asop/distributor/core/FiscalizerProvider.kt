package ru.asop.distributor.core

import ru.asop.distributor.sync.SyncStore

/**
 * Промпт 017: построение `fiscalUrl` для QR-кода чека дистрибьютора.
 *
 * Привязка фискализатора к дистрибьютору приходит JSON-пуллом из admin-service
 * (ASOP_DISTRIBUTOR_FISCALIZERS → ASOP_FISCALIZERS). Страница чека = `baseApiUrl`
 * (хост ОФД) + `/processing-api/receipts/{receiptId}` (совпадает с
 * `BifitAdapter.receiptUrl` на сервере). В dev seed `BIFIT.BASE_API_URL=
 * http://mock-fiscal:8089` → QR указывает на dev mock-ОФД; в prod host-root Bifit
 * даёт `https://fp.bifit.com/processing-api/receipts/{id}`. Без справочников —
 * фолбэк на публичный шаблон Bifit.
 */
class FiscalizerProvider(private val store: SyncStore) {

    private companion object {
        const val BIFIT_RECEIPT_TEMPLATE = "https://fp.bifit.com/processing-api/receipts/"
        const val GENERIC_RECEIPT_PATH = "/processing-api/receipts/"
    }

    /** Актуальный fiscalUrl для `receiptId`. Никогда не бросает — фолбэк на BIFIT-шаблон. */
    suspend fun resolveUrl(cardsDistributorId: String, receiptId: String): String {
        val link = store.distributorFiscalizers()
            .firstOrNull { it.cardsDistributorId == cardsDistributorId && it.isActive && it.deletedAt == null }
            ?: return BIFIT_RECEIPT_TEMPLATE + receiptId
        val fiscalizer = runCatching {
            store.fiscalizers()
                .firstOrNull { it.fiscalizerId == link.fiscalizerId && it.deletedAt == null }
        }.getOrNull()
        val baseApiUrl = link.customApiUrl ?: fiscalizer?.baseApiUrl
        return when {
            !baseApiUrl.isNullOrBlank() -> baseApiUrl.trimEnd('/') + GENERIC_RECEIPT_PATH + receiptId
            else -> BIFIT_RECEIPT_TEMPLATE + receiptId
        }
    }
}