package ru.asop.payment.core

import android.content.Context
import android.util.Log
import com.ftpos.library.smartpos.emv.Amount
import com.ftpos.library.smartpos.emv.CandidateAIDInfo
import com.ftpos.library.smartpos.emv.Emv
import com.ftpos.library.smartpos.emv.OnEmvResponse
import com.ftpos.library.smartpos.emv.OnSearchCardCallback
import com.ftpos.library.smartpos.emv.TrackData
import com.ftpos.library.smartpos.emv.TransRequest
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Зонд через ЛИЦЕНЗИОННОЕ EMV-ядро FTSDK (`Emv`), а не сырой `NfcReader`.
 *
 * `Emv.searchCardWithoutEMV` — поиск карты БЕЗ EMV-транзакции: детектирует карту и отдаёт
 * TrackData (Track2 содержит PAN). Отвечает на вопрос «доступно ли ядро нашему приложению»
 * и «читается ли карта через легальный путь» (в отличие от `NfcReader.openCardEx` → 89).
 */
class EmvProbe private constructor(private val context: Context) {

    private val tag = "EmvProbe"

    data class Result(
        val connected: Boolean,
        val cardType: Int,
        val track1: String?,
        val track2: String?,
        val track3: String?,
        val panTag5A: String?,
        val track2Tag57: String?,
        val error: String?
    ) {
        fun toJson(): String = JSONObject()
            .put("connected", connected)
            .put("cardType", cardType)
            .put("track1", track1 ?: JSONObject.NULL)
            .put("track2", track2 ?: JSONObject.NULL)
            .put("track3", track3 ?: JSONObject.NULL)
            .put("panTag5A", panTag5A ?: JSONObject.NULL)
            .put("track2Tag57", track2Tag57 ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
            .toString()
    }

    /** Блокирует до детекта карты (или timeout). Держать карту на антенне во время вызова. */
    fun probe(timeoutMs: Long = 20_000): Result {
        // Обеспечиваем bind PosServer (тот же, что для NfcReader).
        CardProbe.get(context).reader()

        val emv = runCatching { Emv.getInstance(context) }.getOrNull()
            ?: return Result(false, -1, null, null, null, null, null, "Emv.getInstance() == null (нет bind PosServer?")

        val typeRef = AtomicInteger(-1)
        val trackRef = AtomicReference<TrackData?>()
        val errRef = AtomicInteger(Int.MIN_VALUE)
        val latch = CountDownLatch(1)

        val started = runCatching {
            emv.searchCard(2, object : OnSearchCardCallback {
                override fun onSuccess(cardType: Int, trackData: TrackData?) {
                    typeRef.set(cardType)
                    trackRef.set(trackData)
                    latch.countDown()
                }

                override fun onError(code: Int) {
                    errRef.set(code)
                    latch.countDown()
                }
            })
        }.isSuccess

        if (!started) return Result(false, -1, null, null, null, null, null, "searchCardWithoutEMV не стартовал")

        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return if (!done) {
            runCatching { emv.stopEMV() }
            Result(false, -1, null, null, null, null, null, "searchCardWithoutEMV timeout — держите карту на антенне")
        } else if (errRef.get() != Int.MIN_VALUE) {
            Result(false, -1, null, null, null, null, null, "searchCardWithoutEMV error=${errRef.get()}")
        } else {
            val td = trackRef.get()
            // Карта в поле — пробуем вычитать PAN (тег 5A) и Track2 (тег 57) из EMV-данных.
            val pan = runCatching { emv.getCardData("5A") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { emv.getTlvList("5A") }.getOrNull()?.takeIf { it.isNotBlank() }
            val track2 = runCatching { emv.getCardData("57") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { emv.getTlvList("57") }.getOrNull()?.takeIf { it.isNotBlank() }
            Result(
                connected = true,
                cardType = typeRef.get(),
                track1 = td?.getTrack1Data()?.takeIf { it.isNotBlank() },
                track2 = td?.getTrack2Data()?.takeIf { it.isNotBlank() },
                track3 = td?.getTrack3Data()?.takeIf { it.isNotBlank() },
                panTag5A = pan,
                track2Tag57 = track2,
                error = null
            )
        }
    }

    companion object {
        @Volatile private var instance: EmvProbe? = null
        fun get(context: Context): EmvProbe =
            instance ?: synchronized(this) {
                instance ?: EmvProbe(context.applicationContext).also { instance = it }
            }
    }

    /**
     * Полная EMV-транзакция `startEMV` (ЗДЕСЬ карта отдаёт PAN через READ RECORD/GPO).
     * Без загруженных AID/CAPK скорее всего вернёт «нет поддерживаемого приложения», но
     * фиксируем весь флоу (поиск → select → PAN → online → end).
     */
    fun startEmvProbe(timeoutMs: Long = 40_000): String {
        CardProbe.get(context).reader()
        val emv = runCatching { Emv.getInstance(context) }.getOrNull()
            ?: return """{"error":"Emv.getInstance() null"}"""

        val panRef = AtomicReference<String?>()
        val endRef = AtomicReference<String?>()
        val appRef = AtomicReference<String?>()
        val onlineRef = AtomicReference<String?>()
        val flow = StringBuilder()
        val latch = CountDownLatch(1)

        val amount = Amount(10000L, 0L)
        val req = TransRequest(0, "643").setCardType(2).setVerifyPinSkip(true)

        val started = runCatching {
            emv.startEMV(amount, req, object : OnEmvResponse {
                override fun onAppSelect(isMatched: Boolean, candidates: MutableList<CandidateAIDInfo>?) {
                    val aids = candidates?.mapNotNull { c ->
                        c.getDFName_tag84()?.let { b -> b.joinToString("") { "%02X".format(it) } }
                    }?.take(6)
                    appRef.set("matched=$isMatched count=${candidates?.size} aid=$aids")
                    flow.append("[select:$isMatched]")
                }

                override fun onPinEntry(cardHolderConfirmed: Int) {
                    flow.append("[pin=$cardHolderConfirmed]")
                }

                override fun onOnlineProcess(authData: String?) {
                    onlineRef.set(authData)
                    flow.append("[online]")
                }

                override fun onEndProcess(code: Int, data: String?) {
                    endRef.set("code=$code data=$data")
                    flow.append("[end=$code]")
                    latch.countDown()
                }

                override fun onDisplayPanInfo(pan: String?) {
                    panRef.set(pan)
                    flow.append("[PAN=$pan]")
                }

                override fun onSearchCard() {
                    flow.append("[seek]")
                    // Ядро просит приложение само искать карту — запускаем searchCard(2).
                    runCatching {
                        emv.searchCard(2, object : OnSearchCardCallback {
                            override fun onSuccess(cardType: Int, trackData: TrackData?) {
                                flow.append("[found:$cardType]")
                            }

                            override fun onError(code: Int) {
                                flow.append("[sErr:$code]")
                            }
                        })
                    }
                }

                override fun onSearchCardAgain() {
                    flow.append("[seekAgain]")
                    runCatching {
                        emv.searchCard(2, object : OnSearchCardCallback {
                            override fun onSuccess(cardType: Int, trackData: TrackData?) {
                                flow.append("[foundAgain:$cardType]")
                            }

                            override fun onError(code: Int) {
                                flow.append("[sErr2:$code]")
                            }
                        })
                    }
                }

                override fun onProcessInteractionPoint(point: Int) { flow.append("[pt=$point]") }

                override fun onObtainData(type: Int, data: ByteArray?, additional: ByteArray?) {
                    flow.append("[obtain=$type]")
                }

                override fun onUpdateTransAmount(): Amount { return amount }
            })
        }.isSuccess

        if (!started) return """{"error":"startEMV не стартовал"}"""

        val ended = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { emv.stopEMV() }

        return JSONObject()
            .put("ended", ended)
            .put("pan", panRef.get() ?: JSONObject.NULL)
            .put("endProcess", endRef.get() ?: JSONObject.NULL)
            .put("appSelect", appRef.get() ?: JSONObject.NULL)
            .put("online", onlineRef.get() ?: JSONObject.NULL)
            .put("flow", flow.toString())
            .toString()
    }
}
