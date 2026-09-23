package ru.asop.distributor.sync

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.delay
import ru.asop.distributor.BuildConfig
import ru.asop.distributor.cert.CertManager
import ru.asop.distributor.network.CertSignApi
import ru.asop.distributor.network.DistributorSyncApi
import ru.asop.distributor.network.models.CertSignRequest
import ru.asop.distributor.network.models.CertStoredResult
import ru.asop.distributor.network.models.DistributorRegisterRequest

/**
 * Серверный контур дистрибьютора:
 *   1. cert-sign (ECC P-256 + HMAC, polling) → mTLS-идентичность
 *   2. register (upsert ASOP_DISTRIBUTOR_TERMINALS по terminalSerial=ANDROID_ID)
 *   3. pull asop_keys + asop_tariff_rates (JSON-/delta, versionSince курсоры) → SyncStore
 */
class ProvisionSyncManager(
    private val context: Context,
    private val certManager: CertManager,
    private val store: SyncStore,
    private val certSignApi: CertSignApi,
    private val syncApi: DistributorSyncApi,
    private val moshi: Moshi
) {

    sealed interface Result {
        data class Ok(
            val distributorTerminalId: String?,
            val keysCount: Int,
            val tariffsCount: Int
        ) : Result

        data class Fail(val message: String) : Result
    }

    companion object {
        private const val TAG = "DistributorSync"
        private const val POLL_INTERVAL_MS = 2000L
        private const val POLL_TIMEOUT_MS = 5 * 60 * 1000L
    }

    fun getAndroidId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

    /** Полный цикл: certify (при отсутствии) → register → sync keys/tariffs. */
    suspend fun registerAndSync(): Result {
        try {
            val serial = getAndroidId().ifBlank { return Result.Fail("ANDROID_ID пуст") }
            store.setSerial(serial)
            provision(serial)
            return registerAndPull(serial)
        } catch (e: Exception) {
            Log.w(TAG, "registerAndSync error: ${e.message}")
            return Result.Fail(e.message ?: "Unknown error")
        }
    }

    private suspend fun provision(serial: String) {
        if (certManager.hasCertificate()) return
        if (!certManager.hasKeyPair()) certManager.generateKeyPair()

        val response = certSignApi.requestCertSign(
            CertSignRequest(
                terminalSerial = serial,
                terminalNumber = serial.takeLast(6),
                terminalModel = "Feitian F20",
                distributor = true,
                cardsDistributorId = BuildConfig.DISTRIBUTOR_CARDS_DISTRIBUTOR_ID.ifBlank { null },
                paymentProviderId = BuildConfig.PAYMENT_PROVIDER_ID.ifBlank { null },
                publicKeyBase64 = certManager.getPublicKeyBase64()
            )
        )
        if (!response.isSuccessful) {
            throw IllegalStateException("cert-sign HTTP ${response.code()}")
        }
        val eventId = response.body()?.eventId
            ?: throw IllegalStateException("cert-sign: no eventId")

        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val status = certSignApi.getEventStatus(eventId, eventId)
            if (!status.isSuccessful) throw IllegalStateException("event poll HTTP ${status.code()}")
            val body = status.body() ?: throw IllegalStateException("event poll: empty")
            when (body.state) {
                "PENDING" -> continue
                "FAILED" -> throw IllegalStateException("cert-sign failed: ${body.errorMessage ?: "unknown"}")
                "COMPLETED" -> {
                    val adapter: JsonAdapter<CertStoredResult> = moshi.adapter(CertStoredResult::class.java)
                    val result = adapter.fromJson(body.resultData ?: throw IllegalStateException("cert-sign: empty resultData"))
                        ?: throw IllegalStateException("cert-sign: bad resultData")
                    val terminalPem = buildString {
                        appendLine("-----BEGIN CERTIFICATE-----")
                        append(result.certificateBase64)
                        appendLine()
                        appendLine("-----END CERTIFICATE-----")
                    }
                    certManager.storeCertificateChain(listOf(terminalPem, result.caChain))
                    return
                }
                else -> throw IllegalStateException("unknown event state: ${body.state}")
            }
        }
        throw IllegalStateException("cert-sign timeout")
    }

    private suspend fun registerAndPull(serial: String): Result {
        val reg = syncApi.register(
            DistributorRegisterRequest(
                terminalSerial = serial,
                terminalNumber = serial.takeLast(6),
                terminalModel = "Feitian F20",
                cardsDistributorId = BuildConfig.DISTRIBUTOR_CARDS_DISTRIBUTOR_ID
                    .ifBlank { null },
                paymentProviderId = BuildConfig.PAYMENT_PROVIDER_ID
            )
        )
        if (!reg.isSuccessful) {
            return if (reg.code() == 400) {
                Result.Fail("register HTTP 400: для новой серии нужен cardsDistributorId — задайте distributor.cards.distributor.id или назначьте дистрибьютора в web-admin")
            } else {
                Result.Fail("register HTTP ${reg.code()}")
            }
        }
        val terminalId = reg.body()?.distributorTerminalId
        store.setDistributorTerminalId(terminalId)

        val keysResp = syncApi.keysDelta(store.lastKeysVersion())
        if (!keysResp.isSuccessful) return Result.Fail("keys/delta HTTP ${keysResp.code()}")
        val keys = keysResp.body() ?: emptyList()
        store.mergeKeys(keys)

        val tariffsResp = syncApi.tariffsDelta(store.lastTariffsVersion())
        if (!tariffsResp.isSuccessful) return Result.Fail("tariffs/delta HTTP ${tariffsResp.code()}")
        val tariffs = tariffsResp.body() ?: emptyList()
        store.mergeTariffs(tariffs)

        val maxKeysVersion = keys.map { it.version ?: 0L }.maxOrNull()
        val maxTariffsVersion = tariffs.map { it.version ?: 0L }.maxOrNull()
        store.setKeysVersion(
            maxKeysVersion ?: store.lastKeysVersion(),
            maxTariffsVersion ?: store.lastTariffsVersion()
        )
        store.markSyncedAt(System.currentTimeMillis())

        Log.i(TAG, "sync done: terminal=$terminalId, keys=${store.keys().size}, tariffs=${store.tariffs().size}")
        return Result.Ok(
            distributorTerminalId = terminalId,
            keysCount = store.keys().size,
            tariffsCount = store.tariffs().size
        )
    }
}