package ru.asop.payment.core

import org.json.JSONObject
import java.util.UUID

/** Локальный статус платежа (документ bank_card_api.md §5). */
enum class PayStatus {
    APPROVED, DECLINED, TIMEOUT, DEFERRED, CANCELED, PENDING, VOIDED, VOID_FAILED;

    companion object {
        fun from(s: String?): PayStatus = entries.firstOrNull { it.name == s } ?: CANCELED
    }
}

/**
 * Запрос платежа из локального POST /pay или handoff (EXTRA_REQUEST).
 * Поля — по документу bank_card_api.md §1.2.
 */
data class PayRequest(
    val requestId: String,
    val amount: Double,
    val currency: String,
    val paymentType: String,
    val capture: Boolean,
    val sessionId: String?,
    val transactionId: String?,
    val message: String?,
    val terminalSerial: String? = null,
    val carrierId: String? = null,
    val regionId: String? = null,
    val issuedAt: Long? = null
) {
    fun toJson(): String = JSONObject()
        .put("requestId", requestId)
        .put("amount", amount)
        .put("currency", currency)
        .put("paymentType", paymentType)
        .put("capture", capture)
        .optPut("sessionId", sessionId)
        .optPut("transactionId", transactionId)
        .optPut("message", message)
        .optPut("terminalSerial", terminalSerial)
        .optPut("carrierId", carrierId)
        .optPut("regionId", regionId)
        .optPut("issuedAt", issuedAt)
        .toString()

    companion object {
        fun fromJson(json: String): PayRequest = fromJson(JSONObject(json))

        fun fromJson(o: JSONObject): PayRequest = PayRequest(
            requestId = o.optString("requestId"),
            amount = o.optDouble("amount", 0.0),
            currency = o.optString("currency", "RUB"),
            paymentType = o.optString("paymentType", "FARE"),
            capture = o.optBoolean("capture", true),
            sessionId = o.optString("sessionId").ifBlank { null },
            transactionId = o.optString("transactionId").ifBlank { null },
            message = o.optString("message").ifBlank { null },
            terminalSerial = o.optString("terminalSerial").ifBlank { null },
            carrierId = o.optString("carrierId").ifBlank { null },
            regionId = o.optString("regionId").ifBlank { null },
            issuedAt = if (o.has("issuedAt") && !o.isNull("issuedAt")) o.optLong("issuedAt") else null
        )
    }
}

/** Карточные данные ответа (см. §1.2 /pay). */
data class CardInfo(
    val maskedPan: String?,
    val panLast4: String?,
    val bin: String?,
    val expiry: String?,
    val holdername: String?,
    val cardToken: String?
) {
    fun toJson(): JSONObject = JSONObject()
        .optPut("maskedPan", maskedPan)
        .optPut("panLast4", panLast4)
        .optPut("bin", bin)
        .optPut("expiry", expiry)
        .optPut("holdername", holdername)
        .optPut("cardToken", cardToken)
}

/** Ответ POST /pay / GET /status. Всегда содержит requestId + paymentId. */
data class PayResponse(
    val requestId: String,
    val status: PayStatus,
    val paymentId: String?,
    val amount: Double?,
    val acqReference: String?,
    val rrn: String?,
    val authCode: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val approvedOffline: Boolean,
    val card: CardInfo?
) {
    fun toJson(): String = JSONObject()
        .put("requestId", requestId)
        .put("status", status.name)
        .optPut("paymentId", paymentId)
        .optPut("amount", amount)
        .optPut("acqReference", acqReference)
        .optPut("rrn", rrn)
        .optPut("authCode", authCode)
        .optPut("errorCode", errorCode)
        .optPut("errorMessage", errorMessage)
        .put("approvedOffline", approvedOffline)
        .apply { card?.let { put("card", it.toJson()) } }
        .toString()

    companion object {
        fun fromJson(o: JSONObject): PayResponse = PayResponse(
            requestId = o.optString("requestId"),
            status = PayStatus.from(o.optString("status")),
            paymentId = o.optString("paymentId").ifBlank { null },
            amount = if (o.has("amount") && !o.isNull("amount")) o.optDouble("amount") else null,
            acqReference = o.nullable("acqReference"),
            rrn = o.nullable("rrn"),
            authCode = o.nullable("authCode"),
            errorCode = o.nullable("errorCode"),
            errorMessage = o.nullable("errorMessage"),
            approvedOffline = o.optBoolean("approvedOffline", false),
            card = if (o.has("card") && !o.isNull("card")) {
                val c = o.getJSONObject("card")
                CardInfo(
                    c.nullable("maskedPan"),
                    c.nullable("panLast4"),
                    c.nullable("bin"),
                    c.nullable("expiry"),
                    c.nullable("holdername"),
                    c.nullable("cardToken")
                )
            } else null
        )
    }
}

/** Idempotent-ключи для /pay: requestId → ответ сохраняется в Room. */
class PaymentKey {
    companion object {
        fun newId(): String = com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch().toString()
    }
}

private fun JSONObject.optPut(key: String, value: Any?): JSONObject =
    if (value == null) { put(key, JSONObject.NULL); this } else put(key, value)

/** null-safe чтение строки: JSONObject.NULL/отсутствие/"null"/пустоё → null. */
private fun JSONObject.nullable(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key)
    return v.takeIf { it.isNotBlank() && it != "null" }
}