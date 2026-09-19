package ru.asop.distributor.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.asop.distributor.BuildConfig
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Клиент локального HTTP-API app-payment (bank_card_api.md §1).
 * Подпись HMAC-SHA256 по timestamp, тело не подписывается.
 */
class PaymentClient {

    data class Result(
        val requestId: String,
        val status: String,        // APPROVED | DECLINED | TIMEOUT | DEFERRED | CANCELED | PENDING
        val paymentId: String?,
        val acqReference: String?,
        val errorCode: String?,
        val errorMessage: String?
    )

    suspend fun pay(
        requestId: String,
        amount: Double,
        paymentType: String,
        sessionId: String? = null,
        message: String? = null
    ): Result? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("requestId", requestId)
            .put("amount", amount)
            .put("currency", "RUB")
            .put("paymentType", paymentType)
            .put("capture", true)
            .putOpt("sessionId", sessionId)
            .putOpt("message", message)

        val status = post("/pay", body.toString())
        val result = status?.let { parse(it) }
        // PENDING (re-auth) — поллим статус пару раз
        var polled = result
        if (result?.status == "PENDING") {
            repeat(3) {
                kotlinx.coroutines.delay(1_000)
                val s = get("/status/$requestId")
                if (s != null) {
                    polled = parse(s)
                    if (polled?.status != "PENDING") return@repeat
                }
            }
        }
        polled
    }

    suspend fun status(requestId: String): Result? = withContext(Dispatchers.IO) {
        get("/status/$requestId")?.let { parse(it) }
    }

    // ---------- transport ----------

    private fun post(path: String, body: String): String? = request("POST", path, body)
    private fun get(path: String): String? = request("GET", path, null)

    private fun request(method: String, path: String, body: String?): String? = try {
        val url = URL(BuildConfig.PAYMENT_APP_HOST + path)
        val ts = System.currentTimeMillis().toString()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            this.requestMethod = method
            connectTimeout = 25_000
            readTimeout = 30_000
            setRequestProperty("x-timestamp", ts)
            setRequestProperty("x-signature", Hmac.sign(BuildConfig.PAYMENT_APP_KEY, ts))
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(body.toByteArray().size)
            }
        }
        body?.let { OutputStreamWriter(conn.outputStream).use { it.write(body) } }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (Log.isLoggable("PaymentClient", Log.DEBUG)) {
            Log.d("PaymentClient", "$method $path → $code $resp")
        }
        resp
    } catch (e: Exception) {
        Log.w("PaymentClient", "request $method $path error: ${e.message}")
        null
    }

    private fun parse(json: String): Result? = try {
        val o = JSONObject(json)
        Result(
            requestId = o.optString("requestId"),
            status = o.optString("status", "UNKNOWN"),
            paymentId = o.nullable("paymentId"),
            acqReference = o.nullable("acqReference"),
            errorCode = o.nullable("errorCode"),
            errorMessage = o.nullable("errorMessage")
        )
    } catch (e: Exception) {
        null
    }

    private fun JSONObject.putOpt(key: String, value: Any?): JSONObject =
        if (value == null) { put(key, JSONObject.NULL); this } else put(key, value)

    private fun JSONObject.nullable(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key)
        return v.takeIf { it.isNotBlank() && it != "null" }
    }
}

object Hmac {
    private val HEX = "0123456789abcdef".toCharArray()

    fun sign(secret: String, timestampMillis: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(timestampMillis.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}