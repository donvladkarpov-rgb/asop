package ru.asop.payment.core

import android.content.Context
import android.util.Log
import com.ftpos.library.smartpos.nfcreader.ISO14443_PollingInfo
import com.ftpos.library.smartpos.nfcreader.NfcReader
import com.ftpos.library.smartpos.nfcreader.OnNfcPollingCallback
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Диагностический зонд банковской карты через FTSDK `NfcReader` (БЕЗ секретных ключей).
 *
 * Отвечает на вопрос «отдаёт ли F20 карту нашему приложению» (PCI caveat) и что реально
 * читается без ключевой инъекции:
 *   1. `openCard` → ATR/ATS контактной карты;
 *   2. `sendApduCustomer(SELECT PPSE 2PAY.SYS.DDF01)` → список платёжных AID (открытый текст).
 *
 * ПАН не парсится здесь — только подтверждение доступа к карте. Read-only, карту не трогает.
 */
class BankCardProbe private constructor(private val context: Context) {

    private val tag = "BankCardProbe"

    data class Result(
        val connected: Boolean,
        val type: Int,
        val atsHex: String?,
        val uidHex: String?,
        val ppseRc: Int,
        val ppseHex: String?,
        val error: String?
    ) {
        fun toJson(): String = JSONObject()
            .put("connected", connected)
            .put("type", type)
            .put("atsHex", atsHex ?: JSONObject.NULL)
            .put("uidHex", uidHex ?: JSONObject.NULL)
            .put("ppseRc", ppseRc)
            .put("ppseHex", ppseHex ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
            .toString()
    }

    /** Блокирует до детекта карты (или timeout). Держать карту на антенне во время вызова. */
    fun probe(timeoutMs: Long = 15_000): Result {
        val reader = CardProbe.get(context).reader()
            ?: return Result(false, -1, null, null, -1, null, "NfcReader недоступен (ServiceManager bind failed)")

        val infoRef = AtomicReference<ISO14443_PollingInfo?>()
        val errRef = AtomicReference<Int?>()
        val latch = CountDownLatch(1)

        fun finish(f: () -> Result): Result {
            runCatching { reader.close() }
            return f()
        }

        return try {
            reader.openCardEx(0, object : OnNfcPollingCallback {
                override fun onSuccess(info: ISO14443_PollingInfo?) {
                    infoRef.set(info)
                    latch.countDown()
                }

                override fun onError(code: Int) {
                    errRef.set(code)
                    latch.countDown()
                }
            })

            val opened = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!opened) {
                return finish { Result(false, -1, null, null, -1, null, "openCardEx timeout — карта не детектирована (поднесите карту и повторите)") }
            }
            errRef.get()?.let { code ->
                return finish { Result(false, -1, null, null, code, null, "openCardEx error=$code") }
            }

            val info = infoRef.get()

            val ppse = byteArrayOf(
                0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
                0x0E.toByte(),
                0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53, 0x2E, 0x44, 0x44, 0x46, 0x30, 0x31,
                0x00
            )
            val resp = ByteArray(512)
            val respLen = IntArray(1)
            val rc = reader.sendApduCustomer(ppse, ppse.size, resp, respLen)

            finish {
                Result(
                    connected = true,
                    type = info?.type ?: -1,
                    atsHex = info?.ats?.let { hex(it) },
                    uidHex = info?.uid?.let { hex(it) },
                    ppseRc = rc,
                    ppseHex = if (respLen[0] > 0) hex(resp.copyOf(respLen[0].coerceAtMost(512))) else null,
                    error = null
                )
            }
        } catch (e: Exception) {
            finish { Result(false, -1, null, null, -1, null, "probe error: ${e.javaClass.simpleName} ${e.message}") }
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    companion object {
        @Volatile private var instance: BankCardProbe? = null
        fun get(context: Context): BankCardProbe =
            instance ?: synchronized(this) {
                instance ?: BankCardProbe(context.applicationContext).also { instance = it }
            }
    }
}
