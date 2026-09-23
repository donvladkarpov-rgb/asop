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
 * `Emv.searchCard` — поиск карты (детект + ATS). PAN карта отдаёт только внутри
 * полной EMV-транзакции `startEMV` (SELECT→GPO→READ RECORD), поэтому `probe()` даёт
 * «карта найдена», а `startEmvProbe()` доводит до `onEndProcess` и читает PAN из `getTlvList("5A")`.
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

        if (!started) return Result(false, -1, null, null, null, null, null, "searchCard не стартовал")

        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return if (!done) {
            runCatching { emv.stopEMV() }
            Result(false, -1, null, null, null, null, null, "searchCard timeout — держите карту на антенне")
        } else if (errRef.get() != Int.MIN_VALUE) {
            Result(false, -1, null, null, null, null, null, "searchCard error=${errRef.get()}")
        } else {
            val td = trackRef.get()
            Result(
                connected = true,
                cardType = typeRef.get(),
                track1 = td?.getTrack1Data()?.takeIf { it.isNotBlank() },
                track2 = td?.getTrack2Data()?.takeIf { it.isNotBlank() },
                track3 = td?.getTrack3Data()?.takeIf { it.isNotBlank() },
                panTag5A = null,
                track2Tag57 = null,
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
     * Полная EMV-транзакция `startEMV` — порт `EmptyEventHandler` из легаси `feitian-hardware`.
     *
     * КЛЮЧЕВОЕ: ядро EMV — интерактивная стейт-машина. Приложение ОБЯЗАНО отвечать ядру на
     * каждый колбэк (`respondEvent`/`setTLV`/`setIssuerOnlineResponseData`), иначе флоу виснет
     * на `cbWaitCard` и не доходит до `onEndProcess` (это и происходило в прошлых попытках).
     *
     * PAN читается из `getTlvList("5A")` ВНУТРИ `onEndProcess` (после GPO + READ RECORD, который
     * ядро делает само); fallback — track2 (`57`), срок — `5F24`.
     */
    fun startEmvProbe(timeoutMs: Long = 60_000): String {
        CardProbe.get(context).reader()
        val emv = runCatching { Emv.getInstance(context) }.getOrNull()
            ?: return """{"error":"Emv.getInstance() null"}"""

        val panRef = AtomicReference<String?>()
        val track2Ref = AtomicReference<String?>()
        val expRef = AtomicReference<String?>()
        val endRef = AtomicReference<String?>()
        val appRef = AtomicReference<String?>()
        val onlineRef = AtomicReference<String?>()
        val flow = StringBuilder()
        val latch = CountDownLatch(1)

        val amount = Amount(100L, 0L)

        // Попытка загрузить минимальный МИР-AID (иначе «Valid AID Num: 0» → приложение не разрешено).
        flow.append("[loadAid:${loadMirAid(emv)}]")

        val searchCardCallback = object : OnSearchCardCallback {
            override fun onSuccess(type: Int, trackData: TrackData?) {
                flow.append("[found:$type]")
                runCatching { emv.respondEvent(null) }
            }

            override fun onError(errCode: Int) {
                flow.append("[sErr:$errCode]")
                runCatching { emv.stopEMV() }
                latch.countDown()
            }
        }

        val handler = object : OnEmvResponse {
            override fun onAppSelect(reselect: Boolean, list: MutableList<CandidateAIDInfo>?) {
                val aids = list?.mapNotNull { c ->
                    c.getDFName_tag84()?.let { b -> b.joinToString("") { "%02X".format(it) } }
                }?.take(6)
                appRef.set("reselect=$reselect count=${list?.size} aid=$aids")
                flow.append("[appSelect:${list?.size}]")
            }

            override fun onPinEntry(cvm: Int) {
                flow.append("[pin]")
                runCatching { emv.respondEvent(null) }
            }

            override fun onOnlineProcess(data: String?) {
                onlineRef.set(data)
                flow.append("[online]")
                runCatching { emv.setIssuerOnlineResponseData(0, null, "00", null, null, null) }
                runCatching { emv.respondEvent(null) }
            }

            override fun onEndProcess(code: Int, data: String?) {
                endRef.set("code=$code")
                flow.append("[end=$code]")
                if (code == 89) {
                    latch.countDown()
                    return
                }
                panRef.set(getTlvValue(emv.getTlvList("5A"), "5A"))
                track2Ref.set(getTlvValue(emv.getTlvList("57"), "57"))
                expRef.set(getTlvValue(emv.getTlvList("5F24"), "5F24"))
                latch.countDown()
            }

            override fun onDisplayPanInfo(s: String?) {
                if (!s.isNullOrBlank()) panRef.set(s)
                flow.append("[PAN=$s]")
            }

            override fun onSearchCard() {
                flow.append("[seek]")
                // Референс использует searchCard(1, …) (карта уже на поле при старте транзакции).
                // У нас транзакция стартует первой — даём 20с, чтобы успеть поднести карту.
                runCatching { emv.searchCard(20, searchCardCallback) }
            }

            override fun onSearchCardAgain() {
                flow.append("[seekAgain]")
                runCatching { emv.searchCard(20, searchCardCallback) }
            }

            override fun onProcessInteractionPoint(step: Int) {
                flow.append("[step=$step]")
                runCatching { emv.respondEvent(null) }
            }

            override fun onObtainData(coed: Int, data: ByteArray?, dataInformation: ByteArray?) {
                flow.append("[obtain=$coed]")
                val tagHex = data?.joinToString("") { "%02X".format(it) }
                if (coed == 5) { // IKernelINSInfo.TAG_LIST
                    when (tagHex) {
                        "1F3E" -> runCatching { emv.setTLV("1F3E", "00000000") }
                        "1F10" -> runCatching { emv.setTLV("1F10", "01") }
                    }
                }
                if (coed == 1) { // IKernelINSInfo.TLV_DATA
                    runCatching { emv.setTLV("1F6A", "5A081122334455667788DF81100101") }
                }
                runCatching { emv.respondEvent(null) }
            }

            override fun onUpdateTransAmount(): Amount { return amount }
        }

        val req = TransRequest(0)
            .setmCurrencyCode("643")
            .setCardType(2)   // ICardType.TYPE_CARD_CONTACT_LESS
            .setVerifyPinSkip(true)
            .setMagTransQuickPass(false)
            .setMagTransServiceCodeProcess(true)
            .setMaxTimeoutEMVThreadWait(30)
            .setReadRecordCallback(true)
            .setEnableAppSelectCallback(false)
            .setNeedBeep(false)
            .setSeePhoneContinueTrans(false)
            .setAdditionalTlvData("1F300101")

        val started = runCatching { emv.startEMV(amount, req, handler) }.isSuccess
        if (!started) return """{"error":"startEMV не стартовал"}"""

        val ended = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { emv.stopEMV() }

        return JSONObject()
            .put("ended", ended)
            .put("pan", panRef.get() ?: JSONObject.NULL)
            .put("track2", track2Ref.get() ?: JSONObject.NULL)
            .put("exp", expRef.get() ?: JSONObject.NULL)
            .put("endProcess", endRef.get() ?: JSONObject.NULL)
            .put("appSelect", appRef.get() ?: JSONObject.NULL)
            .put("online", onlineRef.get() ?: JSONObject.NULL)
            .put("flow", flow.toString())
            .toString()
    }

    /** Извлекает значение TLV-тега из hex-строки `tlvData` (формат «TTLLVV…»). */
    private fun getTlvValue(tlvData: String?, tag: String): String? {
        if (tlvData.isNullOrEmpty()) return null
        val tagIndex = tlvData.indexOf(tag)
        if (tagIndex < 0) return null
        val lengthStart = tagIndex + tag.length
        if (lengthStart + 2 > tlvData.length) return null
        val lengthHex = tlvData.substring(lengthStart, lengthStart + 2)
        val valueLength = lengthHex.toIntOrNull(16)?.times(2) ?: return null
        if (lengthStart + 2 + valueLength > tlvData.length) return null
        return tlvData.substring(lengthStart + 2, lengthStart + 2 + valueLength)
    }

    /** Загрузка параметров бесконтактного приложения (EMVCL) из assets/emv/EMVCL_AppParameters.xml. */
    private fun loadMirAid(emv: Emv): String = EmvclParamsLoader.load(context, emv)
}
