package ru.asop.payment.core

import android.content.Context
import android.util.Log
import com.ftpos.library.smartpos.nfcreader.ISO14443_PollingInfo
import com.ftpos.library.smartpos.nfcreader.NfcCardType
import com.ftpos.library.smartpos.nfcreader.NfcReader
import com.ftpos.library.smartpos.nfcreader.OnNfcPollingCallback
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Ручной EMV-ридер банковской карты по сырым APDU через FTSDK `NfcReader` (НЕ ядро EMV).
 *
 * Тот же подход, что devnied/EMV-NFC-Paycard-Enrollment — публичные данные карты читаются
 * последовательностью SELECT PPSE → SELECT AID → GET PROCESSING OPTIONS → READ RECORD,
 * БЕЗ терминальных параметров эквайера и БЕЗ `manageEmvclAppParameters` (который у нас
 * заблокирован rc=5). Доступны: PAN (маскируем), имя держателя, срок, label приложения,
 * AID, Track2-equivalent.
 *
 * ВАЖНО (F20): открывать карту надо как RF — `openCardEx(CARD_TYPE_RF=2, …)`, а не 0 (IC).
 * Иначе `openCardEx(0)` → error=89 (ERR_OP_TIMEOUT) на бесконтактной карте.
 */
class EmvCardReader private constructor(private val context: Context) {

    private val tag = "EmvCardReader"

    data class CardData(
        val connected: Boolean,
        val pan: String,
        val maskedPan: String,
        val cardholderName: String,
        val expDate: String,
        val appLabel: String,
        val aid: String,
        val track2: String,
        val error: String?
    ) {
        fun toJson(): String = org.json.JSONObject()
            .put("connected", connected)
            .put("pan", pan)
            .put("maskedPan", maskedPan)
            .put("cardholderName", cardholderName)
            .put("expDate", expDate)
            .put("appLabel", appLabel)
            .put("aid", aid)
            .put("track2", track2)
            .put("error", error ?: org.json.JSONObject.NULL)
            .toString()

        companion object {
            fun failure(error: String) = CardData(false, "", "", "", "", "", "", "", error)
        }
    }

    fun read(timeoutMs: Long = 15_000): CardData {
        val reader = CardProbe.get(context).reader()
            ?: return CardData.failure("NfcReader недоступен")
        var opened = false
        try {
            val infoRef = AtomicReference<ISO14443_PollingInfo?>()
            val errRef = AtomicReference<Int?>()
            val latch = CountDownLatch(1)

            reader.openCardEx(NfcCardType.CARD_TYPE_RF, object : OnNfcPollingCallback {
                override fun onSuccess(info: ISO14443_PollingInfo?) {
                    infoRef.set(info); latch.countDown()
                }
                override fun onError(code: Int) {
                    errRef.set(code); latch.countDown()
                }
            })

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return CardData.failure("openCardEx timeout — поднесите карту")
            }
            errRef.get()?.let { return CardData.failure("openCardEx error=$it") }
            opened = true

            // 1. SELECT PPSE (2PAY.SYS.DDF01)
            val ppse = hex("00 A4 04 00 0E 325041592E5359532E4444463031 00")
            val ppseResp = transceive(reader, ppse) ?: return CardData.failure("PPSE нет ответа")

            Log.i(tag, "PPSE raw(${ppseResp.size}): ${ppseResp.toHex()}")

            val aids = extractAids(ppseResp)
            Log.i(tag, "PPSE candidates: $aids")

            // Приоритет МИР, затем остальные по порядку
            val ordered = aids.sortedBy { if (it.startsWith("A000000658")) 0 else 1 }
            if (ordered.isEmpty()) return CardData.failure("нет платёжных AID в PPSE")

            // 2..4. Для каждого AID: SELECT → GPO → READ RECORD
            for (aid in ordered) {
                val selResp = transceive(reader, selectAid(aid))
                if (selResp == null) {
                    Log.i(tag, "SELECT $aid → нет ответа")
                    continue
                }
                Log.i(tag, "SELECT $aid resp(${selResp.size}): ${selResp.toHex()}")
                val label = tagAscii(selResp, "50")
                val pdol = findTag(selResp, "9F38")?.value

                val gpoResp = tryGpo(reader, pdol, aid)
                if (gpoResp == null) {
                    Log.i(tag, "GPO(all variants) $aid → отрицание")
                    continue
                }
                Log.i(tag, "GPO $aid resp(${gpoResp.size}): ${gpoResp.toHex()}")
                val afl = findTag(gpoResp, "94")?.value
                if (afl == null) {
                    Log.i(tag, "GPO $aid → нет AFL")
                    continue
                }
                Log.i(tag, "AFL $aid: ${afl.toHex()}")

                val records = readRecords(reader, afl)
                Log.i(tag, "records $aid: ${records.size} шт")
                val pan = recordsTag(records, "5A")?.toHex()
                if (pan == null) {
                    Log.i(tag, "$aid no PAN in records")
                    continue
                }
                val name = recordsTag(records, "5F20")?.ascii() ?: ""
                val exp = recordsTag(records, "5F24")?.toHex() ?: ""
                val track2 = recordsTag(records, "57")?.toHex() ?: ""

                val expFmt = if (exp.length >= 4) "${exp.substring(2, 4)}/${exp.substring(0, 2)}" else exp
                val nameClean = name.trim().let { if (it == "/" || it.isBlank()) "" else it }

                return CardData(
                    connected = true,
                    pan = pan,
                    maskedPan = maskPan(pan),
                    cardholderName = nameClean,
                    expDate = expFmt,
                    appLabel = label ?: "",
                    aid = aid,
                    track2 = track2,
                    error = null
                )
            }
            return CardData.failure("PAN не извлечён ни из одного AID")
        } catch (e: Exception) {
            return CardData.failure("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            if (opened) runCatching { reader.close() }
        }
    }

    // ---------- APDU-уровень ----------

    private fun transceive(reader: NfcReader, apdu: ByteArray): ByteArray? {
        val resp = ByteArray(512)
        val len = IntArray(1)
        val rc = reader.sendApduCustomer(apdu, apdu.size, resp, len)
        if (rc != 0) {
            Log.i(tag, "APDU rc=$rc cmd=${apdu.toHex()}")
            return null
        }
        if (len[0] <= 2) {
            Log.i(tag, "APDU SW=${resp.copyOf(len[0]).toHex()} cmd=${apdu.toHex()}")
            return null
        }
        return resp.copyOf(len[0])
    }

    private fun selectAid(aidHex: String): ByteArray {
        val aid = aidHex.fromHex()
        val cmd = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)
        return cmd
    }

    private fun gpo(pdol: ByteArray?): ByteArray {
        val data: ByteArray = if (pdol == null || pdol.isEmpty()) {
            byteArrayOf(0x83.toByte(), 0x00)
        } else {
            val body = buildPdol(pdol)
            byteArrayOf(0x83.toByte(), body.size.toByte()) + body
        }
        return byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, data.size.toByte()) + data
    }

    /** Пробует несколько вариантов GPO (полный PDOL / пустой / без 83-шаблона / CLA=00). */
    private fun tryGpo(reader: NfcReader, pdol: ByteArray?, aid: String): ByteArray? {
        val variants = mutableListOf<Pair<String, ByteArray>>()

        variants.add("full" to gpo(pdol))

        val empty = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, 0x02, 0x83.toByte(), 0x00)
        variants.add("empty" to empty)

        if (pdol != null && pdol.isNotEmpty()) {
            val vals = buildPdolValues(pdol)
            val no83 = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, vals.size.toByte()) + vals
            variants.add("values-no83" to no83)
            val with83 = byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, (vals.size + 2).toByte(), 0x83.toByte(), vals.size.toByte()) + vals
            variants.add("values-83" to with83)
        }

        for ((name, apdu) in variants) {
            val resp = transceive(reader, apdu)
            if (resp != null) {
                Log.i(tag, "GPO[$name] $aid OK resp(${resp.size})")
                return resp
            }
        }
        return null
    }

    /** Только значения PDOL (без tag/length) — для части карт. */
    private fun buildPdolValues(pdol: ByteArray): ByteArray {
        val entries = parsePdol(pdol)
        val out = ByteArrayOutputStream()
        for ((tg, len) in entries) {
            val def = PDOL_DEFAULTS[tg] ?: "".padStart(len * 2, '0')
            out.write(def.fromHex().copyOf(len))
        }
        return out.toByteArray()
    }

    private fun buildPdol(pdol: ByteArray): ByteArray {
        val entries = parsePdol(pdol)
        val out = ByteArrayOutputStream()
        for ((tg, len) in entries) {
            val def = PDOL_DEFAULTS[tg] ?: "".padStart(len * 2, '0')
            val v = def.fromHex()
            out.write(tg.fromHex())
            out.write(len)
            out.write(v.copyOf(len))
        }
        return out.toByteArray()
    }

    private fun readRecords(reader: NfcReader, afl: ByteArray): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        var i = 0
        while (i + 4 <= afl.size) {
            val sfi = afl[i].toInt() and 0xFF
            val first = afl[i + 1].toInt() and 0xFF
            val last = afl[i + 2].toInt() and 0xFF
            val p2 = (sfi and 0xF8) or 0x04
            for (rec in first..last) {
                val cmd = byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), p2.toByte(), 0x00)
                transceive(reader, cmd)?.let { records.add(it) }
            }
            i += 4
        }
        return records
    }

    // ---------- TLV ----------

    private fun extractAids(fci: ByteArray): List<String> {
        val aids = mutableListOf<String>()
        collectTags(fci, setOf("4F"), aids)
        // fallback: если 4F не нашли, пробуем 84 (только внутри 61)
        if (aids.isEmpty()) collectTags(fci, setOf("84"), aids)
        return aids.distinct()
    }

    private fun collectTags(bytes: ByteArray, wanted: Set<String>, into: MutableList<String>) {
        for (t in tlvs(bytes)) {
            if (t.tag in wanted) into.add(t.value.toHex())
            collectTags(t.value, wanted, into)
        }
    }

    private fun recordsTag(records: List<ByteArray>, tag: String): ByteArray? {
        for (r in records) {
            findTag(r, tag)?.let { return it.value }
        }
        return null
    }

    private fun tagAscii(tlv: ByteArray, tag: String): String? = findTag(tlv, tag)?.value?.ascii()

    private fun findTag(tlv: ByteArray, tag: String): Tlv? {
        for (sub in tlvs(tlv)) {
            val found = if (sub.tag == tag) sub else findTag(sub.value, tag)
            if (found != null) return found
        }
        return null
    }

    private data class Tlv(val tag: String, val value: ByteArray)

    private fun tlvs(bytes: ByteArray): List<Tlv> {
        val out = mutableListOf<Tlv>()
        var i = 0
        while (i < bytes.size) {
            if (i >= bytes.size) break
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 == 0x00) { i++; continue }
            var tagLen = 1
            if ((b0 and 0x1F) == 0x1F) tagLen = 2
            if (i + tagLen > bytes.size) break
            val tagHex = if (tagLen == 1) "%02X".format(b0)
                else "%02X%02X".format(b0, bytes[i + 1].toInt() and 0xFF)
            i += tagLen
            if (i >= bytes.size) break
            var len = bytes[i].toInt() and 0xFF
            i++
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                len = 0
                repeat(n) { if (i < bytes.size) { len = (len shl 8) or (bytes[i].toInt() and 0xFF); i++ } }
            }
            if (len < 0 || i + len > bytes.size) break
            val value = bytes.copyOfRange(i, i + len)
            i += len
            out.add(Tlv(tagHex, value))
        }
        return out
    }

    private fun parsePdol(pdol: ByteArray): List<Pair<String, Int>> {
        val entries = mutableListOf<Pair<String, Int>>()
        var i = 0
        while (i < pdol.size) {
            val b0 = pdol[i].toInt() and 0xFF
            var tagLen = 1
            if ((b0 and 0x1F) == 0x1F) tagLen = 2
            val tg = if (tagLen == 1) "%02X".format(b0)
                else "%02X%02X".format(b0, pdol[i + 1].toInt() and 0xFF)
            i += tagLen
            if (i >= pdol.size) break
            val len = pdol[i].toInt() and 0xFF
            i++
            entries.add(Pair(tg, len))
        }
        return entries
    }

    private fun maskPan(pan: String): String {
        val p = pan.trimEnd('F').trim()
        return if (p.length <= 10) p else "${p.substring(0, 6)}******${p.substring(p.length - 4)}"
    }

    companion object {
        @Volatile private var instance: EmvCardReader? = null
        fun get(context: Context): EmvCardReader =
            instance ?: synchronized(this) {
                instance ?: EmvCardReader(context.applicationContext).also { instance = it }
            }

        fun hex(s: String): ByteArray = s.replace(" ", "").fromHex()
        fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
        fun String.fromHex(): ByteArray {
            val clean = filterNot { it == ' ' || it == ':' }
            if (clean.length % 2 != 0) return ByteArray(0)
            return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        fun ByteArray.ascii(): String = String(this, Charsets.US_ASCII)

        val PDOL_DEFAULTS = mapOf(
            "9F66" to "26000000",   // Terminal Transaction Qualifiers
            "9F02" to "000000000000", // Amount, Authorised
            "9F03" to "000000000000", // Amount, Other
            "9F1A" to "0643",        // Terminal Country Code
            "5F2A" to "0643",        // Currency
            "9A" to "260922",        // Transaction Date (YYMMDD)
            "9C" to "00",            // Transaction Type
            "9F37" to "00000000",    // Unpredictable Number
            "9F35" to "22",          // Terminal Type
            "9F40" to "FF80F0A001",  // Additional Terminal Capabilities
            "9F7A" to "01",          // (МИР) contactless terminal indicator
            "5F2D" to "7275656E",    // Language Preference (ruen)
            "9F1E" to "00000001",    // IFD Serial
            "95" to "0000000000",    // TVR
            "9B" to "000000",        // TSI
        )
    }
}
