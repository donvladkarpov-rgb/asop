package ru.asop.payment.network

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.asop.payment.BuildConfig
import ru.asop.payment.cert.AsopKeyManager
import ru.asop.payment.cert.CertManager
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Клиент отчёта `POST /api/v1/payment/report` (gateway mTLS @Order(2) для /api/v1/payment).
 *
 * До provisioning (cert-sign → DistributionConfig.setPaired) запросы вернутся 401/403, поэтому:
 * - пока pairing не настроен ([isPaired]=false) — DEFERRED (store-and-forward, ничего не теряется);
 * - после pairing — пост с клиентским сертификатом app-payment (AsopKeyManager).
 */
class AcquirerReportClient(context: Context) {

    private val tag = "AcquirerReportClient"
    private val appContext = context.applicationContext
    private val certManager = CertManager(appContext)

    private val mTlsHttp: OkHttpClient by lazy {
        val trustAll = trustAll()
        val keyManager = AsopKeyManager(certManager)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(keyManager), arrayOf(trustAll), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun trustAll(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }

    fun report(paymentId: String, payloadJson: String): ReportResult {
        if (!DistributionConfig.isPaired(appContext)) {
            Log.w(tag, "report deferred (pairing not configured): payment=$paymentId")
            return ReportResult.DEFERRED
        }
        return try {
            val body = payloadJson.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${BuildConfig.GATEWAY_BASE_URL}/api/v1/payment/report")
                .post(body)
                .build()
            mTlsHttp.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.i(tag, "reported payment=$paymentId (${resp.code})")
                    ReportResult.SENT
                } else {
                    Log.w(tag, "report HTTP ${resp.code} for payment=$paymentId: ${resp.body?.string()?.take(200)}")
                    ReportResult.FAILED
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "report failed payment=$paymentId: ${e.message}")
            ReportResult.FAILED
        }
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
