package ru.asop.terminal.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import ru.asop.proto.v1.CardIdentity as ProtoCardIdentity
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Чтение Mifare DESFire (EV1/EV2/EV3) через NFC на терминале.
 *
 * Без аутентификации доступны свободные команды PICC-уровня:
 *  - GetVersion (0x60) — производитель, версии HW/SW, размер, UID, batch, дата выпуска;
 *  - GetFreeMemory (0x6E) — свободная память;
 *  - GetApplicationIDs (0x6A) — список AID приложений.
 * Содержимое файлов/значений требует авторизации приложений — не читается.
 *
 * Основной канал — IsoDep (ISO 14443-4, DESFire в PICC Layer 4). Если карта
 * в Layer 2 (IsoDep нет в techList) — фолбэк на NfcA с native-фреймингом.
 */
object DesfireCardReader {

    private const val TAG = "DesfireCardReader"

    // Native-команды DESFire (Layer 4). Wrapped-APDU (90 INS ...) на этой карте не поддерживается.
    private val GET_VERSION_CMD = byteArrayOf(0x60)
    private val GET_VERSION_FRAMED_CMD = byteArrayOf(0x60, 0x00)
    private val GET_FREE_MEMORY_CMD = byteArrayOf(0x6E)
    private val GET_APPLICATION_IDS_CMD = byteArrayOf(0x6A)
    private val GET_CARD_UID_CMD = byteArrayOf(0x51)
    private val UNDEFINED_CMD = byteArrayOf(0x77)
    private val GET_MORE_FRAMES_CMD = byteArrayOf(0xAF.toByte())

    /** Результат чтения PICC-команд. */
    private data class ReadData(
        val version: DesfireVersion?,
        val freeMemory: Long?,
        val applications: List<String>,
        val ev2Plus: Boolean?,
        val probe: Ev2Probe?,
        val auth: DesfireAuthProbe.AuthResult? = null
    )

    /** Статусы команд-зондов и вывод о поддержке EV2/EV3 (on-card криптографии). */
    private data class Ev2Probe(
        val cardStatus: Int?,
        val undefinedStatus: Int?,
        val verdict: Boolean?
    )

    data class DesfireVersion(
        val hwVendor: Int,
        val hwType: Int,
        val hwSubtype: Int,
        val hwMajor: Int,
        val hwMinor: Int,
        val storageSizeCode: Int,
        val protocolCode: Int,
        val swVendor: Int,
        val swType: Int,
        val swSubtype: Int,
        val swMajor: Int,
        val swMinor: Int,
        val swStorageSize: Int,
        val swProtocolCode: Int,
        val uid: ByteArray,
        val batchNumber: ByteArray,
        val prodDate: IntArray // [year, week]
    ) {
        val uidHex: String get() = uid.toHex()
        val batchHex: String get() = batchNumber.toHex()
        val storageLabel: String get() = storageSizeLabel(storageSizeCode)
        val hwVendorLabel: String get() = vendorLabel(hwVendor)
        val swVendorLabel: String get() = vendorLabel(swVendor)
        val protocolLabel: String get() = protocolLabel(protocolCode)
        val swProtocolLabel: String get() = protocolLabel(swProtocolCode)
        val generation: String get() = when (hwSubtype) {
            0x00 -> "MIFARE DESFire (EV0)"
            0x01 -> "MIFARE DESFire EV1"
            0x02 -> "MIFARE DESFire EV2"
            0x03 -> "MIFARE DESFire EV3"
            else -> "MIFARE DESFire (subtype 0x${String.format("%02X", hwSubtype)})"
        }
    }

    data class AsopIdentity(
        val identityJson: String,
        val signatureBase64: String,
        val protoBytes: ByteArray? = null,
        val signatureValid: Boolean? = null
    )

    data class ReadResult(
        val techs: List<String>,
        val uid: String,
        val atqa: String?,
        val sak: String?,
        val atsHistorical: String?,
        val atsHiLayer: String?,
        val version: DesfireVersion?,
        val freeMemory: Long?,
        val applications: List<String>,
        val ev2Plus: Boolean?,
        val nonGenuineReasons: List<String>,
        val auth: DesfireAuthProbe.AuthResult? = null,
        val identity: AsopIdentity? = null,
        val notes: List<String>,
        val error: String?
    ) {
        val isDesfire: Boolean get() = version != null
    }

    /**
     * Пытается прочитать ASOP cardIdentity с карты: выбирает приложение 0xA05A01,
     * пробует аутентификацию каждым переданным ключом (3K3DES, 0x1A), читает File 0
     * (JSON identity) и File 1 (RSA-PSS подпись base64). Возвращает [AsopIdentity]
     * если чтение удалось, null — если карта не ASOP или ключи не подошли.
     */
    fun readAsopIdentity(tag: Tag, keys: List<ByteArray>): AsopIdentity? {
        val iso = IsoDep.get(tag) ?: return null
        try {
            iso.connect()
            iso.timeout = 3000
            // Выбираем мастер PICC для пробной аутентификации
            if (!selectApp(iso, byteArrayOf(0, 0, 0))) return null
            for (key in keys) {
                if (!authenticate3k3des(iso, key)) continue
                // Мастер PICC аутентифицирован — выбираем ASOP-приложение
                if (!selectApp(iso, aidBytes(ASOP_AID))) continue
                // Аутентификация в ASOP-приложении тем же ключом
                if (!authenticate3k3des(iso, key)) continue
                // Читаем файлы: File 0 = proto CardIdentity, File 1 = signature base64
                val file0bytes = readFile(iso, 0)
                val sigBytes = readFile(iso, 1)
                if (file0bytes == null || sigBytes == null) return null
                val signatureBase64 = sigBytes.toString(Charsets.UTF_8)

                // Пробуем proto (новый формат), fallback на JSON (старый формат)
                val identityJson = try {
                    val p = ProtoCardIdentity.parseFrom(file0bytes)
                    val rolesStr = p.rolesList.joinToString(",") { "\"$it\"" }
                    """{"cardId":"${p.cardId}","uid":"${p.uid}","regionId":"${p.regionId}","organizerId":"${p.organizerId}","carrierId":"${p.carrierId}","cardsDistributorId":"${p.cardsDistributorId}","auditServiceId":"${p.auditServiceId}","userId":"${p.userId}","roles":[$rolesStr]}"""
                } catch (e: Exception) {
                    // Fallback: старый формат (UTF-8 JSON)
                    file0bytes.toString(Charsets.UTF_8)
                }
                return AsopIdentity(identityJson, signatureBase64, protoBytes = file0bytes)
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "readAsopIdentity: ${e.message}")
            return null
        } finally {
            runCatching { iso.close() }
        }
    }

    fun read(tag: Tag): ReadResult {
        val notes = mutableListOf<String>()
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val uid = tag.id.toHex()

        val nfcA = NfcA.get(tag)
        val atqa = nfcA?.let { runCatching { it.atqa.toHex() }.getOrNull() }
        val sak = nfcA?.let { runCatching { String.format("%02X", it.sak.toInt() and 0xFF) }.getOrNull() }

        val iso = IsoDep.get(tag)
        val atsHistorical = iso?.let { runCatching { it.historicalBytes?.toHex() }.getOrNull() }
        val atsHiLayer = iso?.let { runCatching { it.hiLayerResponse?.toHex() }.getOrNull() }

        val data = readDesfireCommands(tag, notes)

        if (data.version == null && data.applications.isEmpty() && data.freeMemory == null) {
            notes += "Свободные команды DESFire не ответили — карта может быть не DESFire или настроена без Layer 4."
        }

        val nonGenuineReasons = data.version?.let { genuinityReasons(it, data.probe) } ?: emptyList()

        return ReadResult(
            techs = techs,
            uid = uid,
            atqa = atqa,
            sak = sak,
            atsHistorical = atsHistorical,
            atsHiLayer = atsHiLayer,
            version = data.version,
            freeMemory = data.freeMemory,
            applications = data.applications,
            ev2Plus = data.ev2Plus,
            nonGenuineReasons = nonGenuineReasons,
            auth = data.auth,
            notes = notes,
            error = null
        )
    }

    /**
     * Читает PICC-информацию карты на уже открытом IsoDep-канале (НЕ закрывает IsoDep).
     * Используется в CardActivationViewModel, чтобы не терять соединение между
     * чтением и попыткой аутентификации.
     */
    fun readWithIsoDep(iso: IsoDep, tag: Tag): ReadResult {
        val notes = mutableListOf<String>()
        val nfcA = NfcA.get(tag)
        val atqa = nfcA?.let { runCatching { it.atqa.toHex() }.getOrNull() }
        val sak = nfcA?.let { runCatching { String.format("%02X", it.sak.toInt() and 0xFF) }.getOrNull() }
        val atsHistorical = iso?.let { runCatching { it.historicalBytes?.toHex() }.getOrNull() }
        val atsHiLayer = iso?.let { runCatching { it.hiLayerResponse?.toHex() }.getOrNull() }
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val uid = tag.id.toHex()
        val version = transceiveDesfire(iso::transceive, GET_VERSION_CMD, notes, "IsoDep GetVersion")
            ?.let { parseVersion(it) }
        val free = transceiveDesfire(iso::transceive, GET_FREE_MEMORY_CMD, notes, "IsoDep GetFreeMemory")
            ?.let { parseFreeMemory(it) }
        val apps = transceiveDesfire(iso::transceive, GET_APPLICATION_IDS_CMD, notes, "IsoDep GetApplicationIDs")
            ?.let { parseAids(it) } ?: emptyList()
        if (version == null && apps.isEmpty() && free == null) {
            notes += "Свободные команды DESFire не ответили"
        }
        return ReadResult(
            techs = techs, uid = uid, atqa = atqa, sak = sak,
            atsHistorical = atsHistorical, atsHiLayer = atsHiLayer,
            version = version, freeMemory = free, applications = apps,
            ev2Plus = null, nonGenuineReasons = emptyList(), notes = notes, error = null
        )
    }

    /**
     * Эвристика genuinity по данным GetVersion и реакции на зонды.
     * Оригинальные NXP-карты: vendor 0x04, subtype 0x01-0x03, HW-версия <= 9,
     * протокол 0x01 (ISO14443-A), и на неизвестную команду отвечают 0x1C
     * (Illegal Command Code). Код памяти НЕ является признаком подлинности —
     * 0x1A (8K) это нормальное значение (размер = 2^(код>>1)).
     */
    private fun genuinityReasons(v: DesfireVersion, probe: Ev2Probe?): List<String> {
        val reasons = mutableListOf<String>()
        if (v.hwVendor != 0x04) {
            reasons += "производитель не NXP (код 0x${String.format("%02X", v.hwVendor)})"
        }
        if (v.hwSubtype !in 1..3) {
            reasons += "неизвестный HW-subtype 0x${String.format("%02X", v.hwSubtype)}"
        }
        if (v.hwMajor > 9) {
            reasons += "HW-версия ${v.hwMajor}.${v.hwMinor} не бывает у оригинальных карт"
        }
        // Код памяти = 2^(code>>1). Нормальный 8K = 0x1A; бьём только по заведомо
        // невалидным кодам (размер вне 512 байт..64 МБ).
        val sizeBits = v.storageSizeCode shr 1
        if (sizeBits < 9 || sizeBits > 26) {
            reasons += "код памяти 0x${String.format("%02X", v.storageSizeCode)} нестандартный"
        }
        if (v.protocolCode != 0x01) {
            reasons += "нестандартный протокол слоя данных 0x${String.format("%02X", v.protocolCode)} (оригинал: ISO14443-A / 0x01)"
        }
        val year = v.prodDate[0]
        if (year !in 2000..2100) {
            reasons += "некорректная дата выпуска ($year г.)"
        }
        val us = probe?.undefinedStatus
        if (us != null && us != 0x1C) {
            reasons += "на неизвестную команду отвечает 0x${String.format("%02X", us)} (оригинал отвечает 0x1C)"
        }
        return reasons
    }

    private fun readDesfireCommands(
        tag: Tag,
        notes: MutableList<String>
    ): ReadData {
        val iso = IsoDep.get(tag)
        if (iso != null) {
            try {
                iso.connect()
                iso.timeout = 3000
                // Сравнение фрейминга: голый 0x60 vs native 0x60 0x00. taginfo шлёт 60 00.
                // Клоны иногда отвечают разными данными в зависимости от фрейма.
                val v1 = transceiveDesfire(iso::transceive, GET_VERSION_CMD, notes, "IsoDep GetVersion(60)")
                val v2 = transceiveDesfire(iso::transceive, GET_VERSION_FRAMED_CMD, notes, "IsoDep GetVersion(60 00)")
                if (v1 != null && v2 != null && !v1.contentEquals(v2)) {
                    notes += "GetVersion различается по фреймингу (60 vs 60 00) — подозрительно"
                }
                val version = (v1 ?: v2)?.let { parseVersion(it) }
                val free = transceiveDesfire(iso::transceive, GET_FREE_MEMORY_CMD, notes, "IsoDep GetFreeMemory")
                    ?.let { parseFreeMemory(it) }
                val apps = transceiveDesfire(iso::transceive, GET_APPLICATION_IDS_CMD, notes, "IsoDep GetApplicationIDs")
                    ?.let { parseAids(it) } ?: emptyList()
                val probe = probeEv2(iso::transceive, notes)
                // Проба криптографии дефолтным нулевым ключом + чтение NXP-сертификата.
                // Только если карта уже подтвердила DESFire — иначе каждый трансивер
                // уходил бы в 3-секундный таймаут на не-DESFire картах.
                val auth = if (version != null) DesfireAuthProbe.probe(iso::transceive) else null
                iso.close()
                if (version == null) notes += "IsoDep: DESFire-команды не распознаны"
                return ReadData(version, free, apps, probe.verdict, probe, auth)
            } catch (e: IOException) {
                notes += "IsoDep: ${e.message}"
            } catch (e: Exception) {
                notes += "IsoDep: ${e.message}"
            }
            return ReadData(null, null, emptyList(), null, null)
        }

        // Фолбэк: Layer 2 (native-фрейминг через NfcA).
        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            try {
                nfcA.connect()
                nfcA.timeout = 3000
                val version = transceiveDesfire(nfcA::transceive, GET_VERSION_CMD, notes, "NfcA GetVersion")
                    ?.let { parseVersion(it) }
                val free = transceiveDesfire(nfcA::transceive, GET_FREE_MEMORY_CMD, notes, "NfcA GetFreeMemory")
                    ?.let { parseFreeMemory(it) }
                val apps = transceiveDesfire(nfcA::transceive, GET_APPLICATION_IDS_CMD, notes, "NfcA GetApplicationIDs")
                    ?.let { parseAids(it) } ?: emptyList()
                val probe = probeEv2(nfcA::transceive, notes)
                nfcA.close()
                if (version == null) notes += "NfcA: DESFire-команды не распознаны"
                return ReadData(version, free, apps, probe.verdict, probe)
            } catch (e: IOException) {
                notes += "NfcA: ${e.message}"
            } catch (e: Exception) {
                notes += "NfcA: ${e.message}"
            }
        }
        return ReadData(null, null, emptyList(), null, null)
    }

    /**
     * Зонд GetCardUID (0x51) — команда EV1+ (появилась в EV1, отсутствовала в EV0),
     * требующая аутентификации master-ключом: без неё оригинал отвечает 0xAE.
     * Оригинальный EV0 на 0x51 ответил бы 0x1C (неизвестная команда). Для контроля
     * шлём заведомо несуществующую команду (0x77): если карта отличает «неизвестную
     * команду» (0x1C) от «требуется auth» (0xAE) для 0x51 — 0x51 ей известна (EV1+).
     */
    private fun probeEv2(
        transceive: (ByteArray) -> ByteArray,
        notes: MutableList<String>
    ): Ev2Probe {
        val cardUid = probeRaw(transceive, GET_CARD_UID_CMD, "GetCardUID")
        val unknown = probeRaw(transceive, UNDEFINED_CMD, "Undefined")
        val cardStatus = cardUid?.firstOrNull()?.let { it.toInt() and 0xFF }
        val unknownStatus = unknown?.firstOrNull()?.let { it.toInt() and 0xFF }
        val cardHex = cardStatus?.let { "0x%02X".format(it) } ?: "—"
        val unknownHex = unknownStatus?.let { "0x%02X".format(it) } ?: "—"
        Log.d(TAG, "probe: GetCardUID=$cardHex, Undefined=$unknownHex")

        val verdict: Boolean? = when {
            cardStatus == 0x00 -> {
                notes += "GetCardUID (0x51) доступен без auth — on-card криптография есть (EV1+)"
                true
            }
            cardStatus == 0xAE && unknownStatus == 0x1C -> {
                notes += "GetCardUID (0x51) известен карте, но требует аутентификацию — EV1+"
                true
            }
            else -> {
                notes += "GetCardUID (0x51) не подтверждён (статус $cardHex; неопределённая команда 0x77 -> $unknownHex)"
                false
            }
        }
        return Ev2Probe(cardStatus, unknownStatus, verdict)
    }

    /** Шлёт команду и возвращает сырой ответ (включая ошибку-статус), либо null при сбое. */
    private fun probeRaw(
        transceive: (ByteArray) -> ByteArray,
        cmd: ByteArray,
        label: String
    ): ByteArray? {
        return try {
            val resp = transceive(cmd)
            Log.d(TAG, "$label ${cmd.toHex()} -> ${resp.toHex()}")
            resp
        } catch (e: Exception) {
            Log.w(TAG, "$label ${cmd.toHex()} fail: ${e.message}")
            null
        }
    }

    /**
     * Отправляет native-команду и собирает полный ответ через GetMoreFrames (0xAF),
     * пока кадры помечены статусом 0xAF. Возвращает только данные (без статус-байтов).
     */
    private fun transceiveDesfire(
        transceive: (ByteArray) -> ByteArray,
        cmd: ByteArray,
        notes: MutableList<String>,
        label: String,
        attempts: Int = 3
    ): ByteArray? {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val out = java.io.ByteArrayOutputStream()
                var resp = transceive(cmd)
                var guard = 0
                // Дополнительные кадры: первый байт 0xAF → запрашиваем следующий кадр.
                while (resp.isNotEmpty() && resp[0] == 0xAF.toByte() && guard++ < 16) {
                    out.write(resp, 1, resp.size - 1)
                    resp = transceive(GET_MORE_FRAMES_CMD)
                }
                if (resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                    out.write(resp, 1, resp.size - 1)
                } else if (resp.isNotEmpty()) {
                    // Статус ошибки (0x7E Length Error и т.п.) — данных нет.
                    Log.d(TAG, "$label status: ${resp.toHex()}")
                    return null
                }
                val data = out.toByteArray()
                Log.d(TAG, "$label ${cmd.toHex()} -> ${data.size} bytes: ${data.toHex()}")
                return data
            } catch (e: Exception) {
                lastError = e
            }
            if (attempt < attempts - 1) Thread.sleep(120)
        }
        Log.w(TAG, "$label fail: ${lastError?.message}")
        return null
    }

    // ---------- парсинг ответов ----------
    // Данные приходят уже без статус-байтов (0xAF/0x00 срезаны в transceiveDesfire).

    private fun parseVersion(data: ByteArray): DesfireVersion? {
        if (data.isEmpty()) return null
        Log.d(TAG, "GetVersion data(${data.size}): ${data.toHex()}")
        return parseVersionData(data)
    }

    private fun parseFreeMemory(data: ByteArray): Long? = parseFreeMemoryData(data)

    private fun parseAids(data: ByteArray): List<String>? {
        if (data.isEmpty() || data.size % 3 != 0) return null
        return chunkedHex(data)
    }

    /** Разбивает байты на трёхбайтовые AID-тройки и форматирует каждую hex-строкой. */
    private fun chunkedHex(bytes: ByteArray): List<String> =
        bytes.toList().chunked(3).map { chunk -> chunk.toByteArray().toHex() }

    /** 28 байт версии: hw/sw(14) + UID(7) + batch(5) + дата(2). */
    private fun parseVersionData(d: ByteArray): DesfireVersion? {
        if (d.size < 28) return null
        // Дата производства упакована в 2 байта BCD: байт 26 = неделя, байт 27 = год (с 2000).
        // Например: 0x34 -> неделя 34, 0x25 -> 2025 г.
        fun bcd(b: Int): Int = ((b shr 4) and 0x0F) * 10 + (b and 0x0F)
        val week = bcd(d[26].toInt() and 0xFF)
        val year = 2000 + bcd(d[27].toInt() and 0xFF)
        return DesfireVersion(
            hwVendor = d[0].toInt() and 0xFF,
            hwType = d[1].toInt() and 0xFF,
            hwSubtype = d[2].toInt() and 0xFF,
            hwMajor = d[3].toInt() and 0xFF,
            hwMinor = d[4].toInt() and 0xFF,
            storageSizeCode = d[5].toInt() and 0xFF,
            protocolCode = d[6].toInt() and 0xFF,
            swVendor = d[7].toInt() and 0xFF,
            swType = d[8].toInt() and 0xFF,
            swSubtype = d[9].toInt() and 0xFF,
            swMajor = d[10].toInt() and 0xFF,
            swMinor = d[11].toInt() and 0xFF,
            swStorageSize = d[12].toInt() and 0xFF,
            swProtocolCode = d[13].toInt() and 0xFF,
            uid = d.copyOfRange(14, 21),
            batchNumber = d.copyOfRange(21, 26),
            prodDate = intArrayOf(year, week)
        )
    }

    /** 3 байта free memory (24-бит big-endian). */
    private fun parseFreeMemoryData(d: ByteArray): Long? {
        if (d.size < 3) return null
        return ((d[0].toLong() and 0xFF) shl 16) or
            ((d[1].toLong() and 0xFF) shl 8) or
            (d[2].toLong() and 0xFF)
    }

    // ---------- метки ----------

    fun vendorLabel(v: Int): String = when (v) {
        0x04 -> "NXP (Philips)"
        0x01 -> "Motorola"
        0x02 -> "Texas Instruments"
        0x05 -> "Infineon"
        0x06 -> "STM"
        0x08 -> "Sony"
        else -> "0x${String.format("%02X", v)}"
    }

    fun protocolLabel(p: Int): String = when (p) {
        0x01 -> "ISO 14443-A"
        0x02 -> "ISO 14443-B"
        0x04 -> "ISO 15693"
        else -> "0x${String.format("%02X", p)}"
    }

    fun storageSizeLabel(code: Int): String {
        // Код памяти DESFire кодируется как 2^(code>>1) байт; бит 0 кода различает
        // "=" (ровно такой размер) и ">" (не менее). Например: 0x1A=8K, 0x18=4K, 0x16=2K.
        if (code in 2..0x3F) {
            val bytes = 1L shl (code shr 1)
            val rel = if ((code and 1) == 0) "=" else ">"
            val label = when {
                bytes >= (1L shl 30) -> "${bytes / (1L shl 30)} ГБ"
                bytes >= (1L shl 20) -> "${bytes / (1L shl 20)} МБ"
                bytes >= (1L shl 10) -> "${bytes / (1L shl 10)} КБ"
                else -> "$bytes байт"
            }
            return "$rel$label (код 0x${String.format("%02X", code)})"
        }
        return "нестандартный код 0x${String.format("%02X", code)}"
    }
}

const val ASOP_AID = 0xA05A01

// ---------- ASOP identity reading (read-only, no ChangeKey/CreateApplication) ----------

private fun selectApp(iso: IsoDep, aid: ByteArray): Boolean {
    val cmd = ByteArray(4)
    cmd[0] = 0x5A
    System.arraycopy(aid, 0, cmd, 1, 3)
    val resp = try { iso.transceive(cmd) } catch (e: Exception) { null }
    return resp != null && resp.size == 1 && (resp[0].toInt() and 0xFF) == 0x00
}

private fun authenticate3k3des(iso: IsoDep, key: ByteArray): Boolean {
    val chResp = try { iso.transceive(byteArrayOf(0x1A, 0x00)) } catch (e: Exception) { null } ?: return false
    val challenge = unwrapAuthFrame(chResp) ?: return false
    if (challenge.size != 8) return false
    val rndB = try {
        val c = Cipher.getInstance("DESede/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DESede"), IvParameterSpec(ByteArray(8)))
        c.doFinal(challenge)
    } catch (e: Exception) { return false }
    val rndA = ByteArray(8).also { SecureRandom().nextBytes(it) }
    val pt = ByteArray(16)
    System.arraycopy(rndA, 0, pt, 0, 8)
    System.arraycopy(rotLeft(rndB), 0, pt, 8, 8)
    val ct = try {
        val c = Cipher.getInstance("DESede/CBC/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DESede"), IvParameterSpec(challenge))
        c.doFinal(pt)
    } catch (e: Exception) { return false }
    val cmd2 = ByteArray(1 + ct.size)
    cmd2[0] = 0xAF.toByte()
    System.arraycopy(ct, 0, cmd2, 1, ct.size)
    val resp2 = try { iso.transceive(cmd2) } catch (e: Exception) { null } ?: return false
    val final = unwrapAuthFrame(resp2) ?: return false
    if (final.size != 8) return false
    val iv2 = ct.copyOfRange(8, 16)
    val dec = try {
        val c = Cipher.getInstance("DESede/CBC/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DESede"), IvParameterSpec(iv2))
        c.doFinal(final)
    } catch (e: Exception) { return false }
    return dec.contentEquals(rotLeft(rndA))
}

private fun readFile(iso: IsoDep, fileNo: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    var offset = 0
    val chunkSize = 32
    while (out.size() < 4096) {
        val cmd = byteArrayOf(0xBD.toByte(), fileNo.toByte(), 0, 0, 0, 0, 0, 0)
        cmd[2] = (offset ushr 16).toByte()
        cmd[3] = (offset ushr 8).toByte()
        cmd[4] = offset.toByte()
        cmd[7] = chunkSize.toByte()
        val resp = try { iso.transceive(cmd) } catch (e: Exception) { null } ?: return null
        val status = resp[0].toInt() and 0xFF
        when {
            status == 0x00 -> {
                out.write(resp, 1, resp.size - 1)
                if (resp.size - 1 < chunkSize) break
                offset += resp.size - 1
            }
            status == 0xAF -> {
                out.write(resp, 1, resp.size - 1)
                offset += resp.size - 1
            }
            status == 0xCE || status == 0x1C -> break
            else -> return null
        }
    }
    return out.toByteArray()
}

private fun unwrapAuthFrame(resp: ByteArray): ByteArray? {
    if (resp.isEmpty()) return null
    return when (resp[0].toInt() and 0xFF) {
        0x00, 0xAF -> resp.copyOfRange(1, resp.size)
        else -> null
    }
}

private fun rotLeft(b: ByteArray): ByteArray {
    val out = ByteArray(b.size)
    for (i in b.indices) out[i] = b[(i + 1) % b.size]
    return out
}

private fun aidBytes(aid: Int): ByteArray = byteArrayOf(
    ((aid ushr 16) and 0xFF).toByte(),
    ((aid ushr 8) and 0xFF).toByte(),
    (aid and 0xFF).toByte()
)

private fun ByteArray.toHex(): String = joinToString(" ") { String.format("%02X", it) }
