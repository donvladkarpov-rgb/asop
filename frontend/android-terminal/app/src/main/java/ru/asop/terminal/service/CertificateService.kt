package ru.asop.terminal.service

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.delay
import ru.asop.terminal.cert.MtlsManager
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.network.CertSignApi
import ru.asop.terminal.network.GatewayApi
import ru.asop.terminal.network.models.CertSignRequest
import ru.asop.terminal.network.models.CertStoredResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сервис первичной персонализации терминала.
 *
 * Шаги:
 *   1. Генерация ECC P-256 ключевой пары в AndroidKeyStore/StrongBox
 *   2. POST /api/v1/terminals/cert-sign на gateway (HTTPS, plain — mTLS недоступен)
 *   3. Polling GET /api/v1/events/{eventId} пока статус COMPLETED или FAILED
 *   4. Сохранение PEM-цепочки в AndroidKeyStore через MtlsManager
 */
@Singleton
class CertificateService @Inject constructor(
    private val mtlsManager: MtlsManager,
    private val certSignApi: CertSignApi,
    private val gatewayApi: GatewayApi,
    private val syncPreferences: SyncPreferences,
    private val moshi: Moshi
) {
    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        private const val POLL_TIMEOUT_MS = 5 * 60 * 1000L  // 5 минут
    }

    enum class State {
        NEEDS_PROVISIONING,
        KEYPAIR_READY,
        CERTIFICATE_READY
    }

    fun getState(): State = when {
        !mtlsManager.hasKeyPair() -> State.NEEDS_PROVISIONING
        !mtlsManager.hasCertificate() -> State.KEYPAIR_READY
        else -> State.CERTIFICATE_READY
    }

    suspend fun provision(
        terminalSerial: String,
        terminalNumber: String? = null,
        terminalModel: String? = null,
        carrierId: String? = null,
        terminalId: String? = null
    ): CertStoredResult {
        // 1. Ключевая пара + публичный ключ
        mtlsManager.generateKeyPair()
        val publicKeyB64 = mtlsManager.getPublicKeyBase64()

        // 2. POST cert-sign (plain HTTPS, без mTLS)
        val response = certSignApi.requestCertSign(
            CertSignRequest(
                terminalSerial = terminalSerial,
                terminalNumber = terminalNumber,
                terminalModel = terminalModel,
                carrierId = carrierId,
                terminalId = terminalId,
                publicKeyBase64 = publicKeyB64
            )
        )

        if (!response.isSuccessful) {
            throw RuntimeException("cert-sign failed: HTTP ${response.code()}")
        }

        val accepted = response.body() ?: throw RuntimeException("cert-sign: empty body")
        val eventId = accepted.eventId

        // 3. Polling
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)

            val statusResponse = certSignApi.getEventStatus(eventIdHeader = eventId, eventId = eventId)
            if (!statusResponse.isSuccessful) {
                throw RuntimeException("getEventStatus failed: HTTP ${statusResponse.code()}")
            }

            val status = statusResponse.body()
                ?: throw RuntimeException("getEventStatus: empty body")

            when (status.state) {
                "PENDING" -> continue
                "FAILED" -> {
                    throw RuntimeException(
                        "Cert sign failed: ${status.errorMessage ?: "unknown"}"
                    )
                }
                "COMPLETED" -> {
                    val result = parseResultData(status.resultData)
                    // 4. Сохранить PEM-цепочку (терминальный сертификат + CA chain)
                    val terminalPem = buildString {
                        appendLine("-----BEGIN CERTIFICATE-----")
                        append(result.certificateBase64)
                        appendLine()
                        appendLine("-----END CERTIFICATE-----")
                    }
                    mtlsManager.storeCertificateChain(listOf(terminalPem, result.caChain))

                    // Загружаем публичный ключ сервера (RSA-PSS для верификации подписей карт)
                    try {
                        val pkResp = gatewayApi.getPublicKey()
                        if (pkResp.isSuccessful) {
                            val pem = buildString {
                                appendLine("-----BEGIN PUBLIC KEY-----")
                                append(pkResp.body()?.publicKeyBase64 ?: "")
                                appendLine()
                                appendLine("-----END PUBLIC KEY-----")
                            }
                            syncPreferences.setServerPublicKey(pem)
                        }
                    } catch (_: Exception) {
                        // Некритично — ключ подтянется позже при следующей синхронизации
                    }

                    return result
                }
                else -> throw RuntimeException("Unknown event state: ${status.state}")
            }
        }

        throw RuntimeException("Cert sign timeout after ${POLL_TIMEOUT_MS / 1000}s")
    }

    private fun parseResultData(json: String?): CertStoredResult {
        if (json.isNullOrEmpty()) {
            throw RuntimeException("Cert stored: empty resultData")
        }
        val adapter: JsonAdapter<CertStoredResult> = moshi.adapter(CertStoredResult::class.java)
        return adapter.fromJson(json)
            ?: throw RuntimeException("Cert stored: failed to parse resultData")
    }
}