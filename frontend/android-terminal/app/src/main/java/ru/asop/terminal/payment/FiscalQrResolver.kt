package ru.asop.terminal.payment

import org.json.JSONObject
import ru.asop.terminal.db.dao.ReferenceRowDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Промпт 017: построение `fiscalUrl` для QR-кода чека **офлайн**.
 *
 * Терминал генерирует `receiptId` локально (UUIDv7) до отправки запроса, поэтому URL
 * известен сразу (не требует ответа сервера). База URL берётся из справочников,
 * доставленных дельтой:
 *   asop_carrier_fiscalizers (carrierId → fiscalizerId / customApiUrl)
 *     → asop_fiscalizers (fiscalizerCode / baseApiUrl).
 *
 * Страница чека = `baseApiUrl` (хост ОФД) + `/processing-api/receipts/{receiptId}`
 * (как `BifitAdapter.receiptUrl` на сервере). В dev seed `BIFIT.BASE_API_URL=`
 * `http://mock-fiscal:8089` → QR указывает на dev mock-ОФД; в prod host-root Bifit
 * даёт `https://fp.bifit.com/processing-api/receipts/{id}` (пример в prompt_017.md).
 * Если справочников нет — фолбэк на публичный шаблон Bifit.
 */
@Singleton
class FiscalQrResolver @Inject constructor(
    private val referenceRowDao: ReferenceRowDao
) {
    private companion object {
        /** Публичный шаблон страницы чека Bifit (фолбэк, совпадает с BifitAdapter на сервере). */
        const val BIFIT_RECEIPT_TEMPLATE = "https://fp.bifit.com/processing-api/receipts/"

        /** Путь страницы чека для произвольного адаптера, если задан baseApiUrl. */
        const val GENERIC_RECEIPT_PATH = "/processing-api/receipts/"
    }

    private data class Fiscalizer(val baseApiUrl: String?)

    /** Активирующий fiscalUrl для `receiptId`. Никогда не бросает — при отсутствии
     *  справочников возвращает дефолтный BIFIT-шаблон. */
    suspend fun resolveUrl(carrierId: String?, receiptId: String): String {
        val fiscalizer = try {
            resolveFiscalizer(carrierId)
        } catch (e: Exception) {
            null
        }
        val base = fiscalizer?.baseApiUrl?.trimEnd('/')
        return when {
            !base.isNullOrBlank() -> base + GENERIC_RECEIPT_PATH + receiptId
            else -> BIFIT_RECEIPT_TEMPLATE + receiptId
        }
    }

    private suspend fun resolveFiscalizer(carrierId: String?): Fiscalizer? {
        val carrierIdSafe = carrierId ?: return null
        val cfRow = referenceRowDao.findActiveCarrierFiscalizerRow(carrierIdSafe) ?: return null
        val cf = JSONObject(cfRow)
        val customApiUrl = cf.optString("customApiUrl").takeIf { it.isNotBlank() }
        val fiscalizerId = cf.optString("fiscalizerId").takeIf { it.isNotBlank() } ?: return null
        val fRow = referenceRowDao.findFiscalizerRowById(fiscalizerId) ?: return null
        val f = JSONObject(fRow)
        val baseApiUrl = customApiUrl ?: f.optString("baseApiUrl").takeIf { it.isNotBlank() }
        return Fiscalizer(baseApiUrl = baseApiUrl)
    }
}
