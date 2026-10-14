package ru.asop.distributor.core

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Ручной EMV-ридер банковской карты по сырым APDU через стандартный Android `IsoDep`
 * (порт `EmvCardReader` из app-payment, который на F20 работает через FTSDK `NfcReader`).
 *
 * Дистрибьютор держит NFC-ридер (reader mode) и сам имеет `Tag`, поэтому читает карту
 * здесь и передаёт открытые данные в `/pay` — на устройствах без FTSDK (Samsung) это
 * единственный способ получить PAN/срок/держателя.
 *
 * Флоу APDU (тот же, что devnied/EMV-NFC-Paycard-Enrollment):
 * SELECT PPSE → SELECT AID → GET PROCESSING OPTIONS (несколько вариантов PDOL) → READ RECORD.
 */
object EmvIsoDepReader {

    private const val TAG = "EmvIsoDep"

    data class Card(
        val maskedPan: String,
        val panLast4: String,
        val bin: String,
        val expiry: String,
        val holdername: String
    )

    /** Читает открытые данные карты из [tag]. null — не банковская карта или чтение не удалось. */
    fun read(tag: Tag): Card? {
        val iso = IsoDep.get(tag)
        if (iso == null) {
            Log.w(TAG, "IsoDep.get(tag)=null, techs=${tag.techList.toList()}")
            return null
        }
        Log.i(TAG, "read start: techs=${tag.techList.toList()}")
        return try {
            iso.connect()
            iso.timeout = 5000
            readConnected(iso)
        } catch (e: Exception) {
            Log.w(TAG, "read failed: ${e.message}")
            null
        } finally {
            runCatching { iso.close() }
        }
    }

    private fun readConnected(iso: IsoDep): Card? {
        // 1. SELECT PPSE (2PAY.SYS.DDF01)
        val ppse = hex("00 A4 04 00 0E 325041592E5359532E4444463031 00")
        val ppseResp = transceive(iso, ppse)
        if (ppseResp == null) { Log.w(TAG, "PPSE: нет ответа"); return null }
        Log.i(TAG, "PPSE raw(${ppseResp.size}): ${ppseResp.toHex()}")

        val aids = extractAids(ppseResp)
        Log.i(TAG, "PPSE candidates: $aids")
        if (aids.isEmpty()) { Log.w(TAG, "PPSE: нет платёжных AID"); return null }

        // Приоритет МИР, затем остальные по порядку.
        val ordered = aids.sortedBy { if (it.startsWith("A000000658")) 0 else 1 }

        for (aid in ordered) {
            val selResp = transceive(iso, selectAid(aid))
            if (selResp == null) { Log.i(TAG, "SELECT $aid → нет ответа"); continue }
            Log.i(TAG, "SELECT $aid resp(${selResp.size}): ${selResp.toHex()}")
            val label = tagAscii(selResp, "50")
            val pdol = findTag(selResp, "9F38")?.value

            val gpoResp = tryGpo(iso, pdol, aid)
            if (gpoResp == null) { Log.i(TAG, "GPO(all variants) $aid → отрицание"); continue }
            Log.i(TAG, "GPO $aid resp(${gpoResp.size}): ${gpoResp.toHex()}")
            val afl = findTag(gpoResp, "94")?.value
            if (afl == null) { Log.i(TAG, "GPO $aid → нет AFL"); continue }
            Log.i(TAG, "AFL $aid: ${afl.toHex()}")

            val records = readRecords(iso, afl)
            Log.i(TAG, "records $aid: ${records.size} шт")
            val pan = recordsTag(records, "5A")?.toHex()
            if (pan == null) { Log.i(TAG, "$aid: нет PAN в records"); continue }
            val name = recordsTag(records, "5F20")?.ascii()?.trim()?.let { if (it == "/" || it.isBlank()) "" else it } ?: ""
            val exp = recordsTag(records, "5F24")?.toHex() ?: ""
            val expFmt = if (exp.length >= 4) "${exp.substring(2, 4)}/${exp.substring(0, 2)}" else exp
            val panClean = pan.trimEnd('F').trim()
            val masked = if (panClean.length <= 10) panClean
                else "${panClean.substring(0, 6)}******${panClean.substring(panClean.length - 4)}"
            Log.i(TAG, "OK $aid: pan=${masked} exp=$expFmt holder=$name")
            return Card(
                maskedPan = masked,
                panLast4 = panClean.takeLast(4),
                bin = panClean.take(6),
                expiry = expFmt,
                holdername = name
            )
        }
        Log.w(TAG, "PAN не извлечён ни из одного AID")
        return null
    }

    // ---------- APDU-уровень ----------

    private fun transceive(iso: IsoDep, apdu: ByteArray): ByteArray? {
        val resp = try { iso.transceive(apdu) } catch (e: Exception) { null } ?: return null
        // Только SW (без данных) = отрицание.
        if (resp.size <= 2) return null
        return resp
    }

    private fun selectAid(aidHex: String): ByteArray {
        val aid = aidHex.fromHex()
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)
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

    private fun tryGpo(iso: IsoDep, pdol: ByteArray?, aid: String): ByteArray? {
        val variants = mutableListOf<ByteArray>()
        variants.add(gpo(pdol))
        variants.add(byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, 0x02, 0x83.toByte(), 0x00))
        if (pdol != null && pdol.isNotEmpty()) {
            val vals = buildPdolValues(pdol)
            variants.add(byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, vals.size.toByte()) + vals)
            variants.add(byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, (vals.size + 2).toByte(), 0x83.toByte(), vals.size.toByte()) + vals)
        }
        for (apdu in variants) {
            val resp = transceive(iso, apdu)
            if (resp != null) {
                Log.i(TAG, "GPO $aid OK resp(${resp.size})")
                return resp
            }
        }
        return null
    }

    private fun buildPdolValues(pdol: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tg, len) in parsePdol(pdol)) {
            val def = PDOL_DEFAULTS[tg] ?: "".padStart(len * 2, '0')
            out.write(def.fromHex().copyOf(len))
        }
        return out.toByteArray()
    }

    private fun buildPdol(pdol: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tg, len) in parsePdol(pdol)) {
            val def = PDOL_DEFAULTS[tg] ?: "".padStart(len * 2, '0')
            val v = def.fromHex()
            out.write(tg.fromHex())
            out.write(len)
            out.write(v.copyOf(len))
        }
        return out.toByteArray()
    }

    private fun readRecords(iso: IsoDep, afl: ByteArray): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        var i = 0
        while (i + 4 <= afl.size) {
            val sfi = afl[i].toInt() and 0xFF
            val first = afl[i + 1].toInt() and 0xFF
            val last = afl[i + 2].toInt() and 0xFF
            val p2 = (sfi and 0xF8) or 0x04
            for (rec in first..last) {
                val cmd = byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), p2.toByte(), 0x00)
                transceive(iso, cmd)?.let { records.add(it) }
            }
            i += 4
        }
        return records
    }

    // ---------- TLV ----------

    private fun extractAids(fci: ByteArray): List<String> {
        val aids = mutableListOf<String>()
        collectTags(fci, setOf("4F"), aids)
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
        for (r in records) findTag(r, tag)?.let { return it.value }
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

    // ---------- hex/ascii helpers ----------

    private fun hex(s: String): ByteArray = s.replace(" ", "").fromHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
    private fun String.fromHex(): ByteArray {
        val clean = filterNot { it == ' ' || it == ':' }
        if (clean.length % 2 != 0) return ByteArray(0)
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun ByteArray.ascii(): String = String(this, Charsets.US_ASCII)

    private val PDOL_DEFAULTS = mapOf(
        "9F66" to "26000000",
        "9F02" to "000000000000",
        "9F03" to "000000000000",
        "9F1A" to "0643",
        "5F2A" to "0643",
        "9A" to "260922",
        "9C" to "00",
        "9F37" to "00000000",
        "9F35" to "22",
        "9F40" to "FF80F0A001",
        "9F7A" to "01",
        "5F2D" to "7275656E",
        "9F1E" to "00000001",
        "95" to "0000000000",
        "9B" to "000000",
    )
}
