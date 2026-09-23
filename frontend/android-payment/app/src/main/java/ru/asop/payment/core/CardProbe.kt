package ru.asop.payment.core

import android.content.Context
import android.util.Log
import com.ftpos.library.smartpos.nfcreader.NfcReader
import com.ftpos.library.smartpos.servicemanager.OnServiceConnectCallback
import com.ftpos.library.smartpos.servicemanager.ServiceManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Фаза 0 промпт 016: проверка доступности NFC-ридера F20 через FTSDK NfcReader.
 *
 * F20 (Feitian) — PCI PTS-терминал: риск, что NFC залочен на платёжное ядро и
 * доступ сторонним приложениям не гарантирован (см. AGENTS.md «Target device»).
 * [checkNfc] отвечает на этот вопрос на реальном устройстве.
 *
 * ВАЖНО: `NfcReader.getInstance(context)` возвращает null ПОКА не выполнен
 * `ServiceManager.bindPosServer(context, callback)` (SystemService FTSDK). Поэтому
 * первым шагом делаем async-bind с ожиданием через CountDownLatch (по образцу
 * feitian-hardware-master `FeitianHardware.init()`).
 */
class CardProbe private constructor(private val context: Context) {

    private val tag = "CardProbe"
    private val bounded = AtomicBoolean(false)

    fun checkNfc(): ProbeResult = runCatching {
        val reader = connectReader()
        if (reader == null) {
            ProbeResult(available = false, checkCode = -2, isExist = false, error = "ServiceManager bind timed out")
        } else {
            val code = reader.checkNFCCardreader()
            val exists = reader.isExist()
            Log.i(tag, "checkNFCCardreader=$code isExist=$exists")
            ProbeResult(available = code == 0, checkCode = code, isExist = exists, error = null)
        }
    }.getOrElse { e ->
        Log.e(tag, "checkNfc failed", e)
        ProbeResult(available = false, checkCode = -1, isExist = false, error = e.message)
    }

    /** Подключённый [NfcReader] (bind SystemService при необходимости). null — недоступен. */
    fun reader(): NfcReader? = connectReader()

    private fun connectReader(): NfcReader? {
        if (bounded.get()) return runCatching { NfcReader.getInstance(context) }.getOrNull()

        // Ретрай: иногда сервис F20 не успевает подняться с первого bind.
        for (attempt in 1..2) {
            val reader = tryConnect()
            if (reader != null) return reader
            runCatching { Thread.sleep(1_500) }
        }
        return null
    }

    private fun tryConnect(): NfcReader? {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        var failCode = -1

        val binder = object : OnServiceConnectCallback {
            override fun onSuccess() {
                ok.set(true)
                latch.countDown()
            }

            override fun onFail(errorCode: Int) {
                failCode = errorCode
                latch.countDown()
            }
        }
        Thread({
            runCatching {
                ServiceManager.bindPosServer(context, binder)
                val connected = latch.await(20, TimeUnit.SECONDS)
                if (connected && ok.get()) {
                    Log.i(tag, "FTSDK ServiceManager connected")
                } else {
                    Log.w(tag, "FTSDK bind failed: connected=$connected ok=${ok.get()} failCode=$failCode")
                }
            }.onFailure { e -> Log.e(tag, "bindPosServer error", e) }
        }, "ftsdk-bind").start()

        return if (latch.await(25, TimeUnit.SECONDS) && ok.get()) {
            bounded.set(true)
            runCatching { NfcReader.getInstance(context) }.getOrNull()
        } else {
            null
        }
    }

    data class ProbeResult(
        val available: Boolean,
        val checkCode: Int,
        val isExist: Boolean,
        val error: String?
    )

    companion object {
        @Volatile private var instance: CardProbe? = null
        fun get(context: Context): CardProbe =
            instance ?: synchronized(this) {
                instance ?: CardProbe(context.applicationContext).also { instance = it }
            }
    }
}