package ru.asop.payment.network

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.asop.payment.BuildConfig
import ru.asop.payment.cert.CertManager
import ru.asop.payment.network.AcquirerReportClient.DistributionConfig
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Provisioning mTLS-идентичности app-payment по образцу distributor (prompt_016 §3.2.4):
 *   cert-sign `POST /api/v1/distributor-terminals/cert-sign` (distributor=true,
 *   cardsDistributorId + paymentProviderId=MOCK-PAY) → poll события → сохранить PEM-цепочку
 *   → DistributionConfig.setPaired(true).
 *
 * app-payment переиспользует distributor cert-sign: сертификат выпускает Intermediate CA,
 * gateway пропускает его на /api/v1/payment (mTLS x509).
 */
class ProvisioningManager(
    private val context: Context,
    private val certManager: CertManager
) {

    companion object {
        private const val TAG = "PaymentProvisioning"
        private const val POLL_INTERVAL_MS = 2000L
        private const val POLL_TIMEOUT_MS = 5 * 60 * 1000L
    }

    private val plainHttp: OkHttpClient by lazy {
        val trustAll = trustAll()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(CertSignHmacInterceptor(BuildConfig.CERT_SIGN_API_KEY, BuildConfig.CERT_SIGN_HMAC_SECRET))
            .build()
    }

    private fun trustAll(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
    }

    suspend fun provision() {
        if (certManager.hasCertificate()) return
        withContext(Dispatchers.IO) {
            try {
                val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                // Префикс нужен: ANDROID_ID одинаков у distributor и app-payment (один debug-ключ),
                // а cert-sign upsert'ится по terminalSerial — без префикса оба сольются в одну строку.
                val serial = "PAY-$androidId"
                if (!certManager.hasKeyPair()) certManager.generateKeyPair()

                val eventId = requestCertSign(serial)
                pollUntilDone(eventId)
                Log.i(TAG, "provisioned cert, paired=true")
                DistributionConfig.setPaired(context, true)
            } catch (e: Exception) {
                Log.e(TAG, "provisioning failed: ${e.message}")
            }
        }
    }

    private fun requestCertSign(serial: String): String {
        val json = JSONObject()
            .put("terminalSerial", serial)
            .put("terminalNumber", serial.takeLast(6))
            .put("terminalModel", "Feitian F20")
            .put("distributor", true)
            .put("cardsDistributorId", BuildConfig.DISTRIBUTOR_CARDS_DISTRIBUTOR_ID.ifBlank { JSONObject.NULL })
            .put("paymentProviderId", BuildConfig.PAYMENT_PROVIDER_ID.ifBlank { JSONObject.NULL })
            .put("publicKeyBase64", certManager.getPublicKeyBase64())
            .toString()

        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${BuildConfig.GATEWAY_BASE_URL}/api/v1/distributor-terminals/cert-sign")
            .post(body)
            .build()
        plainHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("cert-sign HTTP ${resp.code}")
            val respText = resp.body?.string().orEmpty()
            return JSONObject(respText).optString("eventId")
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("cert-sign: no eventId")
        }
    }

    private fun pollUntilDone(eventId: String) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            val req = Request.Builder()
                .url("${BuildConfig.GATEWAY_BASE_URL}/api/v1/events/$eventId")
                .get()
                .build()
            plainHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("event poll HTTP ${resp.code}")
                val obj = JSONObject(resp.body?.string().orEmpty())
                when (obj.optString("state")) {
                    "PENDING" -> return@use
                    "FAILED" -> throw IllegalStateException("cert-sign failed: ${obj.optString("errorMessage")}")
                    "COMPLETED" -> {
                        storeChain(obj.optString("resultData"))
                        return
                    }
                    else -> throw IllegalStateException("unknown event state")
                }
            }
        }
        throw IllegalStateException("cert-sign timeout")
    }

    private fun storeChain(resultData: String) {
        val result = JSONObject(resultData)
        val certificateBase64 = result.optString("certificateBase64")
            .ifBlank { throw IllegalStateException("cert-sign: empty certificateBase64") }
        val caChain = result.optString("caChain")

        val leafPem = buildString {
            appendLine("-----BEGIN CERTIFICATE-----")
            append(certificateBase64)
            appendLine()
            appendLine("-----END CERTIFICATE-----")
        }
        certManager.storeCertificateChain(listOf(leafPem) + (if (caChain.isNotBlank()) listOf(caChain) else emptyList()))

        // База для подсчёта в логе/дебаге (не обязателен — цепочка уже сохранена).
        val decoded = Base64.decode(certificateBase64, Base64.NO_WRAP)
        Log.i(TAG, "stored cert chain: leaf=${decoded.size} bytes, caChain=${if (caChain.isNotBlank()) "present" else "absent"}")
    }
}
