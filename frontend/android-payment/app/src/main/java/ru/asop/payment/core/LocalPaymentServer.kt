package ru.asop.payment.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.asop.payment.BuildConfig
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Локальный HTTP-сервер app-payment на 127.0.0.1:8790 (промпт 016 §1.1).
 *
 * - Только loopback (недоступен снаружи).
 * - HMAC-SHA256 по timestamp (X-API-подпись вещества: X-Request-Id/X-Timestamp/X-Signature).
 * - /capabilities, /pay, /status/{requestId}, /void/{requestId}.
 * - Реализация — минимальный HTTP/1.1 на чистых сокетах (APDU-устройства без веб-граблей).
 */
class LocalPaymentServer private constructor(context: Context) {

    private val tag = "LocalPaymentServer"
    private val appContext = context.applicationContext
    private val processor = PayProcessor(context)
    private val store = PendingPaymentStore.get(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "app-payment-http") {
            runCatching {
                val sock = ServerSocket()
                sock.reuseAddress = true
                sock.bind(InetSocketAddress("127.0.0.1", PORT))
                serverSocket = sock
                Log.i(tag, "listening on $PORT")
                while (running.get()) {
                    val client = runCatching { sock.accept() }.getOrNull() ?: break
                    thread(name = "app-payment-conn", isDaemon = true) { handle(client) }
                }
            }.onFailure { e -> Log.e(tag, "server error", e) }
            running.set(false)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(client: java.net.Socket) {
        client.use { s ->
            try {
                s.soTimeout = 15_000
                val request = HttpRequest.parse(s.getInputStream())
                val response = route(request)
                val body = response.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val out = s.getOutputStream()
                out.write(
                    ("HTTP/1.1 ${response.code} ${response.reason}\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n" +
                            "\r\n").toByteArray(Charsets.UTF_8)
                )
                out.write(body)
                out.flush()
            } catch (e: Exception) {
                Log.w(tag, "conn error: ${e.message}")
            }
        }
    }

    private fun route(req: HttpRequest): HttpResponse {
        val path = req.path
        return when {
            path == METHOD_CAPABILITIES && req.method == "GET" -> respondJson(200, capabilitiesJson())
            path == "/probe/card" && req.method == "GET" -> handleProbeCard()
            path == "/probe/emv" && req.method == "GET" -> handleProbeEmv()
            path == "/probe/startemv" && req.method == "GET" -> handleProbeStartEmv()
            path == "/probe/whitelist" && req.method == "GET" -> handleWhitelist()
            path == "/probe/signcert" && req.method == "GET" -> handleSignCert()
            path == "/probe/emvfiles" && req.method == "GET" -> handleEmvFiles()
            path == "/probe/emvcard" && req.method == "GET" -> handleEmvCard()
            path.startsWith("/probe/kernel/") && req.method == "GET" -> handleSetKernel(path.removePrefix("/probe/kernel/"))
            path.startsWith(METHOD_PAY) && req.method == "POST" -> handlePay(req)
            path.startsWith("/status/") && req.method == "GET" -> handleStatus(req, path.removePrefix("/status/"))
            path.startsWith("/void/") && req.method == "POST" -> handleVoid(req, path.removePrefix("/void/"))
            else -> respondJson(404, """{"error":"not found"}""")
        }
    }

    private val hmacSecret: String = BuildConfig.PAYMENT_HMAC_SECRET

    private fun handlePay(req: HttpRequest): HttpResponse {
        authorize(req)?.let { return it }
        val body = req.body
        val reqObj = runCatching { org.json.JSONObject(body) }.getOrNull()
            ?: return respondJson(400, """{"error":"bad json"}""")
        val request = PayRequest.fromJson(reqObj)
        if (request.requestId.isBlank()) return respondJson(400, """{"error":"requestId required"}""")

        // Идемпотентность: повторный requestId возвращает сохранённый результат.
        val cached = runBlockingOrNull { store.findByIdempotentKey(request.requestId) }
        val result = cached ?: runBlockingOrNull { processor.process(request) }?.also { r ->
            runBlockingOrNull { store.persist(r) }
        } ?: return respondJson(500, """{"error":"internal"}""")
        return respondJson(200, result.toJson())
    }

    private fun handleStatus(req: HttpRequest, requestId: String): HttpResponse {
        val auth = authorize(req)
        if (auth != null) return auth
        val cached = runBlockingOrNull { store.findByIdempotentKey(requestId) }
        return if (cached == null) respondJson(404, """{"error":"unknown request"}""")
        else respondJson(200, cached.toJson())
    }

    private fun handleVoid(req: HttpRequest, requestId: String): HttpResponse {
        val auth = authorize(req)
        if (auth != null) return auth
        val result = runBlockingOrNull { processor.void(PayRequest(requestId, 0.0, "RUB", "FARE", true, null, null, null)) }
            ?: return respondJson(500, """{"error":"internal"}""")
        runBlockingOrNull { store.persist(result) }
        return respondJson(200, result.toJson())
    }

    /** Проверка HMAC-заголовков (промпт 016 §1.1). null → 401/403. */
    private fun authorize(req: HttpRequest?): HttpResponse? {
        if (req == null) return respondJson(400, """{"error":"method not allowed"}""")
        val ts = req.headers["x-timestamp"] ?: return respondJson(401, """{"error":"missing x-timestamp"}""")
        val sig = req.headers["x-signature"] ?: return respondJson(401, """{"error":"missing x-signature"}""")
        val now = System.currentTimeMillis()
        val tsV = ts.toLongOrNull()
        if (tsV == null || kotlin.math.abs(now - tsV) > 300_000L) return respondJson(401, """{"error":"stale timestamp"}""")
        val expected = Hmac.sign(hmacSecret, ts)
        return if (sig == expected) null else respondJson(401, """{"error":"bad signature"}""")
    }

    private fun capabilitiesJson(): String =
        """{"appVersion":"${BuildConfig.VERSION_NAME}","emv":{"gac":true,"contact":false,"contactless":true,"online":true,"offlineFloorLimit":true},"devices":["F20"],"maxAmount":600000.00}"""

    /** Диагностика: real READ карты через FTSDK NfcReader (ATR + SELECT PPSE), без ключей. */
    private fun handleProbeCard(): HttpResponse {
        // Блокирует до детекта карты (timeout 15с) — держать карту на антенне.
        val result = BankCardProbe.get(appContext).probe()
        Log.i(tag, "probe/card -> ${result.toJson()}")
        return respondJson(200, result.toJson())
    }

    /** Диагностика: real READ через лицензионное EMV-ядро FTSDK (searchCardWithoutEMV → Track2/PAN). */
    private fun handleProbeEmv(): HttpResponse {
        val result = EmvProbe.get(appContext).probe()
        Log.i(tag, "probe/emv -> ${result.toJson()}")
        return respondJson(200, result.toJson())
    }

    /** Диагностика: полная EMV-транзакция startEMV (здесь карта отдаёт PAN). */
    private fun handleProbeStartEmv(): HttpResponse {
        val result = EmvProbe.get(appContext).startEmvProbe()
        Log.i(tag, "probe/startemv -> $result")
        return respondJson(200, result)
    }

    /** Эксперимент с KernelID: GET /probe/kernel/{01|02|03|reset} → подмена KernelID при загрузке EMVCL. */
    private fun handleSetKernel(value: String): HttpResponse {
        EmvclParamsLoader.kernelIdOverride =
            if (value == "reset" || value.isBlank()) null else value
        return respondJson(
            200,
            """{"kernelIdOverride":${org.json.JSONObject.quote(EmvclParamsLoader.kernelIdOverride)}}"""
        )
    }

    /** Доступ к API FTSDK: GET /probe/whitelist → проверить/добавить себя в whitelist SysAPI. */
    private fun handleWhitelist(): HttpResponse {
        val result = AsopApiPermission.ensureWhitelisted(appContext)
        Log.i(tag, "probe/whitelist -> $result")
        return respondJson(200, result)
    }

    /** Зарегистрировать подписочный сертификат приложения в FTSDK. */
    private fun handleSignCert(): HttpResponse {
        val result = AsopApiPermission.registerSignCert(appContext)
        Log.i(tag, "probe/signcert -> $result")
        return respondJson(200, result)
    }

    /** Список файловых параметров EMV ядра (SysAPI IEMVFileManager). */
    private fun handleEmvFiles(): HttpResponse {
        val result = EmvclParamsLoader.probeEmvFiles(appContext)
        Log.i(tag, "probe/emvfiles -> $result")
        return respondJson(200, result)
    }

    /** Ручное чтение публичных данных банковской карты (PPSE→SELECT→GPO→READ RECORD). */
    private fun handleEmvCard(): HttpResponse {
        val result = EmvCardReader.get(appContext).read()
        Log.i(tag, "probe/emvcard -> ${result.toJson()}")
        return respondJson(200, result.toJson())
    }

    private data class HttpResponse(val code: Int, val reason: String, val body: String?)
    private fun respondJson(code: Int, body: String): HttpResponse =
        HttpResponse(code, if (code == 200) "OK" else "ERROR", body)

    private fun <T> runBlockingOrNull(block: suspend () -> T): T? =
        runCatching { kotlinx.coroutines.runBlocking { block() } }.getOrNull()

    companion object {
        const val PORT = 8790
        private const val METHOD_PAY = "/pay"
        private const val METHOD_VOID = "/void"
        private const val METHOD_STATUS = "/status"
        private const val METHOD_CAPABILITIES = "/capabilities"

        @Volatile private var instance: LocalPaymentServer? = null
        fun get(context: Context): LocalPaymentServer =
            instance ?: synchronized(this) {
                instance ?: LocalPaymentServer(context.applicationContext).also { instance = it }
            }
    }
}

/** Минимальный парсер HTTP/1.1 request line + headers + body. */
private class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String
) {
    companion object {
        fun parse(input: java.io.InputStream): HttpRequest {
            val lines = mutableListOf<String>()
            var line = readLine(input)
            while (line != null && line.isNotEmpty()) {
                lines.add(line)
                line = readLine(input)
            }
            val first = lines.firstOrNull()?.split(" ") ?: throw IllegalStateException("bad request line")
            require(first.size >= 2)
            val method = first[0]
            val path = first[1].substringBefore('?')
            val headers = buildMap {
                for (i in 1 until lines.size) {
                    val idx = lines[i].indexOf(':')
                    if (idx > 0) {
                        put(
                            lines[i].substring(0, idx).trim().lowercase(),
                            lines[i].substring(idx + 1).trim()
                        )
                    }
                }
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) {
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""
            return HttpRequest(method, path, headers, body)
        }

        private fun readLine(input: java.io.InputStream): String? {
            val sb = StringBuilder()
            var b = input.read()
            if (b < 0) return null
            while (b >= 0) {
                when (b) {
                    '\n'.code -> return sb.toString().trimEnd('\r')
                    else -> sb.append(b.toChar())
                }
                b = input.read()
            }
            return sb.toString()
        }
    }
}