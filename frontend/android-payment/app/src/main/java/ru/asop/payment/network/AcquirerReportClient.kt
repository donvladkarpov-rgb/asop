package ru.asop.payment.network

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Клиент отчёта `POST /api/v1/payment/report` (gateway mTLS @Order(2)).
 *
 * До provisioning (cert-sign + pairing, §3.2.4/§2 банк_card_api.md) app-payment не имеет
 * mTLS-идентичности — запросы вернутся 401/403. Поэтому:
 * - пока pairing не настроен ([isPaired]=false) — платёж остаётся PENDING в очереди
 *   (рабочая модель store-and-forward, ничего не теряется);
 * - после pairing ([PairingStore]) — постык уходит с клиентским сертификатом.
 *
 * Данный класс — seam: транспорт по сертификату подключается в паре с provisioning.
 */
class AcquirerReportClient(context: Context) {

    private val tag = "AcquirerReportClient"
    private val appContext = context.applicationContext

    fun report(paymentId: String, payloadJson: String): ReportResult {
        if (!DistributionConfig.isPaired(appContext)) {
            Log.w(tag, "report deferred (pairing not configured): payment=$paymentId")
            return ReportResult.DEFERRED
        }
        // TODO(Phase 3.2.4): пост с mTLS-client-cert после cert-sign + pairing.
        return ReportResult.DEFERRED
    }

    enum class ReportResult { SENT, DEFERRED, FAILED }

    object DistributionConfig {
        private val Context.dataStore by preferencesDataStore(name = "distribution")

        private val KEY_PAIRED = booleanPreferencesKey("paired")

        /** Pairing считается настроенным, когда provisioning сохранил в DataStore флаг. */
        fun isPaired(context: Context): Boolean = runBlocking {
            runCatching { context.dataStore.data.first()[KEY_PAIRED] }.getOrNull() ?: false
        }

        suspend fun setPaired(context: Context, value: Boolean) {
            context.dataStore.edit { it[KEY_PAIRED] = value }
        }
    }
}