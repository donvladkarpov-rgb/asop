package ru.asop.distributor.core

import ru.asop.distributor.sync.SyncStore

/**
 * Промпт 017 §10: построение `fiscalUrl` для QR-кода чека дистрибьютора.
 *
 * Привязка фискализатора к дистрибьютору приходит JSON-пуллом из admin-service
 * (ASOP_DISTRIBUTOR_FISCALIZERS → ASOP_FISCALIZERS). Для BIFIT (и неизвестного)
 * используется публичный шаблон чека — `baseApiUrl` у BIFIT это API-эндпоинт ОФД,
 * а не страница чека (совпадает с `BifitAdapter.receiptUrl` на сервере).
 */
class FiscalizerProvider(private val store: SyncStore) {

    private companion object {
        const val BIFIT_RECEIPT_TEMPLATE = "https://fp.bifit.com/processing-api/receipts/"
        const val GENERIC_RECEIPT_PATH = "/processing-api/receipts/"
    }

    /** Активирующий fiscalUrl для `receiptId`. Никогда не бросает — фолбэк на BIFIT-шаблон. */
    suspend fun resolveUrl(cardsDistributorId: String, receiptId: String): String {
        val link = store.distributorFiscalizers()
            .firstOrNull { it.cardsDistributorId == cardsDistributorId && it.isActive && it.deletedAt == null }
            ?: return BIFIT_RECEIPT_TEMPLATE + receiptId
        val fiscalizer = runCatching {
            store.fiscalizers()
                .firstOrNull { it.fiscalizerId == link.fiscalizerId && it.deletedAt == null }
        }.getOrNull()
        val code = fiscalizer?.fiscalizerCode
        val baseApiUrl = link.customApiUrl ?: fiscalizer?.baseApiUrl
        return when {
            code.equals("BIFIT", ignoreCase = true) -> BIFIT_RECEIPT_TEMPLATE + receiptId
            code == null -> BIFIT_RECEIPT_TEMPLATE + receiptId
            !baseApiUrl.isNullOrBlank() -> baseApiUrl.trimEnd('/') + GENERIC_RECEIPT_PATH + receiptId
            else -> BIFIT_RECEIPT_TEMPLATE + receiptId
        }
    }
}