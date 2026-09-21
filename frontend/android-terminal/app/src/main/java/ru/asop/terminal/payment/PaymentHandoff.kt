package ru.asop.terminal.payment

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import ru.asop.terminal.BuildConfig
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Промпт 016 §1.3: контракт handoff'а банковской карты терминал → app-payment.
 *
 * Терминал НЕ читает EMV (PCI). Он классифицирует тап ([ru.asop.terminal.nfc.CardClassifier]),
 * гасит свой reader, claim'ит NFC-шину и запускает app-payment foreground-Activity'ей
 * с JSON-запросом в extra. app-payment выполняет EMV + авторизацию и возвращает
 * JSON-результат через setResult(EXTRA_RESULT).
 *
 * КОНТРАКТ (bank_card_api.md §1.2/1.3): ACTION = "ru.asop.payment.ACTION_PAY",
 * EXTRA_REQUEST = "request" — POST /pay body, EXTRA_RESULT = "result" — PayResponse JSON.
 * Константы совпадают с app-payment (`ru.asop.payment.handoff.PaymentHandoff`).
 */
object PaymentHandoff {

    private const val TAG = "PaymentHandoff"

    const val PAYMENT_PACKAGE = "ru.asop.payment"
    const val ACTION_PAY = "ru.asop.payment.ACTION_PAY"
    const val EXTRA_REQUEST = "request"
    const val EXTRA_RESULT = "result"

    /** Локальный HTTP-endpoint app-payment (warm-путь, резерв §3.6). */
    const val LOCAL_BASE_URL = "http://127.0.0.1:8790"
    const val PATH_PAY = "/pay"
    const val HEADER_TIMESTAMP = "X-Timestamp"
    const val HEADER_SIGNATURE = "X-Signature"

    /** HMAC-ключ pairing'а терминал ↔ app-payment (для warm HTTP-пути). */
    private val HMAC_SECRET: ByteArray
        get() = BuildConfig.PAYMENT_HMAC_SECRET.toByteArray(Charsets.UTF_8)

    data class Request(
        val requestId: String,
        val amount: Double,
        val currency: String,
        val paymentType: String = "FARE",
        val capture: Boolean = true,
        val terminalSerial: String,
        val carrierId: String?,
        val regionId: String?,
        val sessionId: String?,
        val message: String? = null,
        val issuedAt: Long
    ) {
        fun toJson(): String = JSONObject().apply {
            put("requestId", requestId)
            put("amount", amount)
            put("currency", currency)
            put("paymentType", paymentType)
            put("capture", capture)
            put("sessionId", sessionId ?: JSONObject.NULL)
            put("transactionId", JSONObject.NULL)
            put("message", message ?: JSONObject.NULL)
            put("terminalSerial", terminalSerial)
            put("carrierId", carrierId ?: JSONObject.NULL)
            put("regionId", regionId ?: JSONObject.NULL)
            put("issuedAt", issuedAt)
        }.toString()

        /** Каноническая строка подписи warm-пути (порядок ключей фиксирован). */
        fun signingPayload(): String =
            listOf(requestId, amount.toString(), currency, terminalSerial, issuedAt.toString())
                .joinToString("|")
    }

    data class Result(
        val success: Boolean,
        val amount: Double,
        val maskedPan: String?,
        val acqReference: String?,
        val rrn: String?,
        val errorMessage: String?,
        val bin: String? = null,
        val cardLast4: String? = null,
        val cardToken: String? = null
    )

    /** BIN-диапазоны МИР (расширенный список + co-badge) — синхронизировано с легаси
     *  `asop-processing-Release-1.6` `Utils.detectPaymentSystem`. */
    private val MIR_BINS = setOf(
        "2200", "2201", "2202", "2203", "2204",
        "3562", "3565", "6234", "6291", "6292", "6711",
        "6763", "6764", "6765", "6768", "6769", "6773",
        "9112", "9417", "5058", "9762", "5614", "9051",
        "9990", "9364", "8888", "8600"
    )

    /**
     * Платёжная система по BIN (первые цифры PAN). МИР (полный диапазон + co-badge 35xx/62xx/67xx/91xx/94xx),
     * Visa 4xxx, MasterCard 5xxx и новое 2-й серии (22–27), UnionPay 62xx. null — не распознано.
     */
    fun paymentSystem(bin: String?): String? = when {
        bin.isNullOrBlank() -> null
        MIR_BINS.any { bin.startsWith(it) } -> "МИР"
        bin.startsWith("4") -> "Visa"
        bin.startsWith("5") -> "MasterCard"
        listOf("22", "23", "24", "25", "26", "27").any { bin.startsWith(it) } -> "MasterCard"
        bin.startsWith("62") -> "UnionPay"
        else -> null
    }

    fun isPaymentAppInstalled(context: Context): Boolean {
        val intent = Intent(ACTION_PAY).setPackage(PAYMENT_PACKAGE)
        return try {
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (e: Exception) {
            Log.w(TAG, "resolveActivity failed: ${e.message}")
            false
        }
    }

    fun buildIntent(request: Request): Intent = Intent(ACTION_PAY).apply {
        setPackage(PAYMENT_PACKAGE)
        putExtra(EXTRA_REQUEST, request.toJson())
    }

    /**
     * Результат из EXTRA_RESULT (PayResponse JSON, см. bank_card_api.md §1.2).
     * APPROVED/VOIDED → success=true; иначе false c errorMessage.
     */
    fun parseResult(resultCode: Int, data: Intent?): Result {
        val json = data?.getStringExtra(EXTRA_RESULT)
        if (json.isNullOrBlank()) {
            return Result(
                success = resultCode == android.app.Activity.RESULT_OK,
                amount = 0.0, maskedPan = null, acqReference = null, rrn = null,
                errorMessage = if (resultCode == android.app.Activity.RESULT_OK)
                    "Пустой ответ приложения оплаты" else null
            )
        }
        return try {
            val obj = JSONObject(json)
            val status = obj.optString("status")
            val approved = status == "APPROVED"
            val card = obj.optJSONObject("card")
            Result(
                success = approved,
                amount = obj.optDouble("amount", 0.0),
                maskedPan = card?.optString("maskedPan").takeIf { !it.isNullOrBlank() },
                acqReference = obj.optString("acqReference").takeIf { it.isNotBlank() },
                rrn = obj.optString("rrn").takeIf { it.isNotBlank() },
                errorMessage = when {
                    approved -> null
                    else -> obj.optString("errorMessage").takeIf { it.isNotBlank() }
                        ?: "Отклонено ($status)"
                }
            )
        } catch (e: Exception) {
            Result(false, 0.0, null, null, null, "Некорректный ответ приложения оплаты")
        }
    }

    fun buildSignature(payload: String): String = try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_SECRET, "HmacSHA256"))
        mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.w(TAG, "buildSignature failed: ${e.message}")
        ""
    }

    fun sha256Hex(value: String): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }
}