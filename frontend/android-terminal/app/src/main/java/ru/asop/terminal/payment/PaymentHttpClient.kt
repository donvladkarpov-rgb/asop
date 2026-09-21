package ru.asop.terminal.payment

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.asop.terminal.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HTTP-клиент локального API app-payment (127.0.0.1:8790, bank_card_api.md §1.1).
 *
 * Терминал НЕ читает банковскую карту (EMV) — только MIFARE. Банковскую карту
 * обрабатывает app-payment (фоновый, FTSDK): терминал шлёт POST /pay по loopback-HTTTP
 * (HMAC-SHA256 по x-timestamp) и поллит /status/{requestId} при PENDING.
 *
 * Порт `PaymentClient` из android-distributor с парсингом в `PaymentHandoff.Result`.
 */
class PaymentHttpClient {

    private val tag = "PaymentHttpClient"

    /** POST /pay (+ poll /status при PENDING). Возвращает [PaymentHandoff.Result] (никогда null). */
    suspend fun pay(request: PaymentHandoff.Request): PaymentHandoff.Result = withContext(Dispatchers.IO) {
        val resp = post("/pay", request.toJson())
            ?: return@withContext PaymentHandoff.Result(
                success = false, amount = request.amount, maskedPan = null,
                acqReference = null, rrn = null, errorMessage = "app-payment недоступен"
            )
        var parsed = parse(resp)
        if (parsed.status == "PENDING") {
            repeat(20) {
                delay(1_000)
                val s = get("/status/${request.requestId}")
                if (s != null) {
                    parsed = parse(s)
                    if (parsed.status != "PENDING") return@repeat
                }
            }
        }
        parsed.toResult()
    }

    // ---------- transport ----------

    private fun post(path: String, body: String): String? = request("POST", path, body)
    private fun get(path: String): String? = request("GET", path, null)

    private fun request(method: String, path: String, body: String?): String? = try {
        val url = URL(PaymentHandoff.LOCAL_BASE_URL + path)
        val ts = System.currentTimeMillis().toString()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 25_000
            readTimeout = 30_000
            setRequestProperty("x-timestamp", ts)
            setRequestProperty("x-signature", hmac(ts))
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(body.toByteArray().size)
            }
        }
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        Log.d(tag, "$method $path -> $code")
        resp
    } catch (e: Exception) {
        Log.w(tag, "$method $path error: ${e.message}")
        null
    }

    private fun hmac(timestampMillis: String): String = try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BuildConfig.PAYMENT_HMAC_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.doFinal(timestampMillis.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }

    // ---------- parse ----------

    private data class Parsed(val status: String, val result: PaymentHandoff.Result)

    private fun parse(json: String): Parsed = try {
        val o = JSONObject(json)
        val status = o.optString("status", "UNKNOWN")
        val approved = status == "APPROVED"
        val card = o.optJSONObject("card")
        Parsed(
            status = status,
            result = PaymentHandoff.Result(
                success = approved,
                amount = o.optDouble("amount", 0.0),
                maskedPan = card?.optString("maskedPan")?.takeIf { it.isNotBlank() },
                acqReference = o.optString("acqReference").takeIf { it.isNotBlank() },
                rrn = o.optString("rrn").takeIf { it.isNotBlank() },
                errorMessage = if (approved) null
                else o.optString("errorMessage").takeIf { it.isNotBlank() } ?: "Отклонено ($status)",
                bin = card?.optString("bin")?.takeIf { it.isNotBlank() },
                cardLast4 = card?.optString("panLast4")?.takeIf { it.isNotBlank() },
                cardToken = card?.optString("cardToken")?.takeIf { it.isNotBlank() }
            )
        )
    } catch (e: Exception) {
        Parsed("UNKNOWN", PaymentHandoff.Result(false, 0.0, null, null, null, "Некорректный ответ app-payment"))
    }

    private fun Parsed.toResult(): PaymentHandoff.Result = result
}
