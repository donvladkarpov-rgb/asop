package ru.asop.terminal.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.util.Log
import ru.asop.proto.v1.CardIdentity as ProtoCardIdentity
import ru.asop.terminal.activation.CardIdentityVcm1
import ru.asop.terminal.nfc.DesfireCardReader.ClassicInfo
import ru.asop.terminal.nfc.DesfireCardReader.ReadResult
import java.io.IOException
import java.util.LinkedHashMap

object MifareClassicReader {

    private const val TAG = "MifareClassicReader"
    private const val TIMEOUT_MS = 30_000L

    fun isMifareClassic(tag: Tag): Boolean {
        return MifareClassic.get(tag) != null
    }

    private val FACTORY_KEYS = listOf(
        byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
        byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte())
    )

    /** VCM1 magic bytes "VCM1" — для парсинга дампа. */
    private val VCM1_MAGIC = byteArrayOf(
        'V'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte()
    )

    fun read(tag: Tag, asopKeyMaterial: List<ByteArray>): ReadResult {
        val mfc = MifareClassic.get(tag) ?: return errorResult(tag, "Not a MifareClassic tag")
        val nfca = NfcA.get(tag)
        val notes = mutableListOf<String>()
        val startTime = System.currentTimeMillis()
        val sectorCount = mfc.sectorCount
        val typeLabel = when {
            sectorCount <= 16 -> "Classic 1K"
            sectorCount <= 40 -> "Classic 4K"
            else -> "Classic (${sectorCount}sectors)"
        }

        var foundKeyFirstBytes: String? = null
        var block0Content: String? = null
        var authSuccess = false
        var allBlocks: Map<Int, List<String>> = emptyMap()
        var trailerLabels: Map<Int, String> = emptyMap()
        var vcm1Identity: CardIdentityVcm1? = null
        /** Если VCM1-magic не найден в sector 1, отмечаем legacy (SAC1/PKCS-empty/...). */
        var cardFormatLegacy: String? = null

        try {
            mfc.connect()
            mfc.timeout = 3000
        } catch (e: java.io.IOException) {
            // Промпт 013: rethrow connect() IOException to caller (Vcm1CardAuth.readWithRetry)
            // — иначе sector-loop проглатывает exception в `outer catch` и возвращает
            // ReadResult c error="Auth failed", скрывая реальную причину.
            Log.w(TAG, "mfc.connect failed (rethrow for retry): ${e.javaClass.simpleName} ${e.message}")
            throw e
        }

        try {
            val candidateKeys = buildCandidateKeys(asopKeyMaterial)
            notes.add("Candidate keys: ${candidateKeys.size} (factory=${FACTORY_KEYS.size}, asop=${asopKeyMaterial.size})")

            // Probe sector 0 (manufacturer block) с factory/ASOP ключами, чтобы получить block 0.
            // Если ни один ключ не подошёл — покажем всем секторам тоже пустые.
            var probedSector0 = false
            for (sectorIndex in 0 until minOf(sectorCount, 1)) {
                if (System.currentTimeMillis() - startTime > TIMEOUT_MS) break
                for (key in candidateKeys) {
                    if (System.currentTimeMillis() - startTime > TIMEOUT_MS) break
                    try {
                        mfc.authenticateSectorWithKeyA(sectorIndex, key)
                        authSuccess = true
                        foundKeyFirstBytes = "${String.format("%02X", key[0])} ${String.format("%02X", key[1])}…"
                        notes.add("Auth OK sector $sectorIndex with key ${key.take(2).joinToString("") { String.format("%02X", it) }}…")
                        probedSector0 = true
                        break
                    } catch (_: Exception) {
                        try {
                            mfc.authenticateSectorWithKeyB(sectorIndex, key)
                            authSuccess = true
                            foundKeyFirstBytes = "${String.format("%02X", key[0])} ${String.format("%02X", key[1])}…"
                            notes.add("Auth OK sector $sectorIndex with key B ${key.take(2).joinToString("") { String.format("%02X", it) }}…")
                            probedSector0 = true
                            break
                        } catch (_: Exception) {
                            // key not working, try next
                        }
                    }
                }
                if (probedSector0) {
                    val block0 = mfc.readBlock(0)
                    block0Content = block0.joinToString(" ") { String.format("%02X", it) }
                    val uidBytes = block0.copyOfRange(0, 4)
                    val bcc = block0[4]
                    notes.add("Block 0: UID=${uidBytes.joinToString("") { String.format("%02X", it) }}, BCC=${String.format("%02X", bcc)}")
                    break
                }
            }

            // Полный дамп sectors 1..15 (Classic 1K) или 1..15 (Classic 4K): читаем все блоки
            // (включая trailers — последний блок сектора). Mixed-state auth: factory → ASOP KeyA/B.
            // Sector 0 пропускаем (manufacturer block, уже прочитали).
            // CRUCIAL: на clone-картах после 2-3 reads CRYPTO1 session умирает — sectors
            // 3..15 auth-fail. Делаем 2 прохода по outer loop: первый пытается все секторы,
            // второй проходит ещё раз и подхватывает то, что не вышло. В итоге в blocksMap
            // остаётся максимум успешно прочитанных блоков.
            val blocksMap = LinkedHashMap<Int, List<String>>()
            val labelsMap = LinkedHashMap<Int, String>()
            val readStart = System.currentTimeMillis()
            for (pass in 0 until 3) {
                if (System.currentTimeMillis() - readStart > TIMEOUT_MS) break
                if (pass > 0) {
                    // Пауза между pass'ами: даём NFC-стеку время на пере-инициализацию
                    // CRYPTO1-сессии. На clone-картах после ~4 reads сессия умирает;
                    // 500ms паузы обычно достаточно для re-init.
                    Log.d(TAG, "Sleep 500ms before pass $pass")
                    Thread.sleep(500L)
                }
                Log.d(TAG, "=== Outer loop pass $pass starting ===")
                for (sector in 1 until sectorCount) {
                    if (System.currentTimeMillis() - readStart > TIMEOUT_MS) {
                        notes.add("Block-dump timeout at sector $sector (pass $pass)")
                        Log.w(TAG, "Block-dump timeout at sector $sector (pass $pass, elapsed ${System.currentTimeMillis() - readStart}ms)")
                        break
                    }
                    // Skip sectors we already read fully OK
                    val existing = blocksMap[sector]
                    val blockCount = try { mfc.getBlockCountInSector(sector) } catch (_: Exception) { 4 }
                    if (existing != null && existing.isNotEmpty() && existing.count { it != "(read failed)" } == blockCount) {
                        Log.d(TAG, "Outer loop pass $pass: skipping sector $sector (already read OK)")
                        continue
                    }
                    Log.d(TAG, "Outer loop pass $pass: starting sector $sector (elapsed ${System.currentTimeMillis() - readStart}ms)")
                    val sr = readSectorBlocks(mfc, sector, candidateKeys, notes)
                    // КРИТИЧНО: никогда не затираем existing! Если в pass 0 успели прочитать
                    // sector 1 (4 OK blocks), а в pass 1 auth-fail и sr.blocks пришёл пустым
                    // (size=0 != existing.size=4) — старый data должен остаться. Иначе мы
                    // теряем 4 OK блока из-за одного failed retтеста.
                    val merged: List<String> = when {
                        existing != null && sr.blocks.size == existing.size -> {
                            existing.zip(sr.blocks).map { (old, new) ->
                                if (old != "(read failed)") old else new
                            }
                        }
                        existing != null -> existing  // размер mismatch — pass 1 вернул меньше; НЕ затираем
                        else -> sr.blocks
                    }
                    blocksMap[sector] = merged
                    if (merged == sr.blocks || labelsMap[sector] == null) {
                        sr.keyLabel?.let { labelsMap[sector] = it }
                    }
                    Log.d(TAG, "Outer loop pass $pass: finished sector $sector → blocks.size=${merged.size}, ok=${merged.count { it != "(read failed)" }}/$blockCount, label=${labelsMap[sector]}")
                }
            }
            val readElapsed = System.currentTimeMillis() - readStart
            notes.add("All-sectors dump: ${blocksMap.size} sectors read in ${readElapsed}ms (2 passes)")
            allBlocks = blocksMap
            trailerLabels = labelsMap

            // Парсим VCM1-payload из sector 1 data-блоков (промпт 008).
            // Только 3 блока: блок 0 (magic+bitmask), блок 1 (cardId), блок 2 (entityUuid).
            // Sector 1 всегда читается первым (sector 0 — manufacturer, sector 1+ — identity).
            val s1 = blocksMap[1]
            if (s1 != null && s1.size >= CardIdentityVcm1.USED_BLOCK_COUNT) {
                val firstThree = s1.take(CardIdentityVcm1.USED_BLOCK_COUNT)
                val allHexOk = firstThree.all { it != "(read failed)" }
                if (allHexOk) {
                    try {
                        val block0Bytes = hexToBytes(firstThree[0])
                        if (block0Bytes.copyOfRange(0, 4).contentEquals(VCM1_MAGIC)) {
                            val block1Bytes = hexToBytes(firstThree[1])
                            val block2Bytes = hexToBytes(firstThree[2])
                            vcm1Identity = CardIdentityVcm1.decodeFromBytes(
                                block0Bytes + block1Bytes + block2Bytes
                            )
                            if (vcm1Identity != null) {
                                notes.add("VCM1 OK — bitmask=0x${vcm1Identity.bitmask.toString(16)}, cardId=${vcm1Identity.cardId}, entity=${vcm1Identity.entity}")
                                Log.i(TAG, "VCM1 parsed successfully: bitmask=0x${vcm1Identity.bitmask.toString(16)}, cardId=${vcm1Identity.cardId}")
                            } else {
                                notes.add("VCM1 magic found but parsing failed (bitmask out of range?)")
                                cardFormatLegacy = "VCM1-CORRUPT"
                            }
                        } else {
                            // Magic != VCM1 → старая SAC1-карта или blank card.
                            cardFormatLegacy = detectLegacyMagic(firstThree[0])
                            notes.add("Sector 1 block 0 magic != VCM1: $cardFormatLegacy")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "VCM1 parse failed: ${e.message}")
                        notes.add("VCM1 parse exception: ${e.message}")
                    }
                } else {
                    notes.add("VCM1 sectors 1 blocks were partially read-failed; can't decode")
                }
            }
        } catch (e: Exception) {
            notes.add("Error: ${e.message}")
            Log.e(TAG, "read outer catch: ${e.javaClass.simpleName} - ${e.message}", e)
        } finally {
            runCatching { if (mfc.isConnected) mfc.close() }
        }

        val elapsed = System.currentTimeMillis() - startTime
        notes.add("Read completed in ${elapsed}ms")

        val uid = tag.id.joinToString("") { String.format("%02X", it) }
        return ReadResult(
            techs = listOfNotNull("MifareClassic", nfca?.tag?.techList?.joinToString(", ")),
            uid = uid,
            atqa = nfca?.atqa?.joinToString(" ") { String.format("%02X", it) },
            sak = String.format("%02X", mfc.tag.id.let { mfc.sectorCount.toByte() }),
            atsHistorical = null,
            atsHiLayer = null,
            version = null,
            freeMemory = null,
            applications = emptyList(),
            ev2Plus = null,
            nonGenuineReasons = emptyList(),
            notes = notes,
            error = if (authSuccess) null else "Auth failed with all keys",
            isClassic = true,
            classicInfo = ClassicInfo(
                sectorCount = sectorCount,
                typeLabel = typeLabel,
                keyFoundFirstBytes = foundKeyFirstBytes,
                block0Content = block0Content,
                allBlocks = allBlocks,
                trailerKeyLabels = trailerLabels,
                sac1Identity = null,  // legacy: SAC1 больше не используется (промпт 008)
                vcm1Identity = vcm1Identity
            )
        )
    }

    /** Префикс trailer'а "SAC1" — magic для SAC1-payload (см. MifareClassicCardWriter.SAC1_MAGIC). */
    private val SAC1_MAGIC = byteArrayOf(
        'S'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte()
    )

    /**
     * Определяет формат карты по hex первого блока sector 1.
     * Возвращает одну из: "VCM1" (новая), "SAC1" (legacy промпт 005/007), "BLANK",
     * "FACTORY_FF", "CUSTOM", "UNKNOWN".
     *
     * Используется для diagnostics — VCM1 — единственный формат активации.
     */
    private fun detectLegacyMagic(block0Hex: String?): String {
        if (block0Hex.isNullOrEmpty() || block0Hex == "(read failed)") return "BLANK"
        return try {
            val bytes = hexToBytes(block0Hex)
            when {
                bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(
                    'V'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte()
                )) -> "VCM1"
                bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(
                    'S'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte()
                )) -> "SAC1"
                bytes.size >= 4 && bytes.all { it == 0x00.toByte() } -> "BLANK"
                bytes.size >= 4 && bytes.all { it == 0xFF.toByte() } -> "FACTORY_FF"
                else -> "CUSTOM"
            }
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    private fun labelKey(key: ByteArray): String = when {
        key.contentEquals(FACTORY_KEYS[0]) -> "factory FF"
        key.contentEquals(FACTORY_KEYS[1]) -> "factory A0"
        key.contentEquals(FACTORY_KEYS[2]) -> "factory D3F7"
        else -> "ASOP ${String.format("%02X", key[0])} ${String.format("%02X", key[1])}…"
    }

    /** hex-строка (32 символа с пробелами) → 16 байт. Бросает IllegalArgumentException при плохом формате. */
    private fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex.replace(" ", "")
        require(cleaned.length % 2 == 0) { "hex length must be even: '$hex'" }
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Mixed-state auth на одном sector и чтение всех блоков (включая trailer).
     * Сначала пробует factory keys, потом ASOP (KeyA/KeyB из terminal_keys).
     * На clone-картах после нескольких блоков сессия утекает: применяем
     * retry-pass с reconnect+reauth для каждого блока, упавшего по IOException.
     */
    private data class SectorRead(val blocks: List<String>, val keyLabel: String?)

    private fun readSectorBlocks(
        mfc: MifareClassic,
        sector: Int,
        candidateKeys: List<ByteArray>,
        notes: MutableList<String>
    ): SectorRead {
        // NB: SessionReset делается в `read()` ОДИН раз перед outer loop.
        // Внутри sector-loop НЕ дёргаем mfc.close/connect — после первого же fail
        // они кидают IOException 'null' на мёртвом Tag, ломая auth для остальных.

        // ВАЖНО: clone emulator (китайские клоны) ВОЗВРАЩАЕТ success на
        // authenticateSectorWithKeyA даже с НЕПРАВИЛЬНЫМ ключом (broken Crypto1).
        // Поэтому НЕ доверяем auth-success — после auth делаем probe-read
        // и если он fail, пробуем следующий ключ. Только когда probe-read OK —
        // считаем auth реальным.
        // 1. Mixed-state auth+verify: factory → ASOP KeyA → ASOP KeyB.
        //    Probe-read = mfc.readBlock(первый block сектора) — read fail = auth был bogus.
        val base = mfc.sectorToBlock(sector)
        var authedKey: ByteArray? = null
        for (key in candidateKeys) {
            try { mfc.authenticateSectorWithKeyA(sector, key) } catch (_: Exception) { continue }
            // Auth прошёл без exception — но это может быть bogus. Verify by read.
            val probeBlock = tryReadFirstBlock(mfc, sector, base)
            if (probeBlock != null) {
                authedKey = key
                notes.add("Auth+verify OK sector $sector with key ${key.take(2).joinToString("") { String.format("%02X", it) }}…")
                break
            }
            // bogus auth — пробуем следующий ключ
        }
        if (authedKey == null) {
            for (key in candidateKeys) {
                try { mfc.authenticateSectorWithKeyB(sector, key) } catch (_: Exception) { continue }
                val probeBlock = tryReadFirstBlock(mfc, sector, base)
                if (probeBlock != null) {
                    authedKey = key
                    notes.add("Auth+verify (KeyB) OK sector $sector with key ${key.take(2).joinToString("") { String.format("%02X", it) }}…")
                    break
                }
            }
        }
        if (authedKey == null) {
            notes.add("readSectorBlocks sector $sector: all keys failed probe-read (likely bogus auth or session lost)")
            return SectorRead(emptyList(), null)
        }

        // 2. Реально authed — читаем все блоки сектора. Per-block retry без reset.
        // timing logs чтобы диагностика читалась.
        val sectorStart = System.currentTimeMillis()
        val blockCount = mfc.getBlockCountInSector(sector)
        val blocks = mutableListOf<String>()
        val sessionKey = authedKey   // захваченный ключ сектора, используем для reauth
        for (b in 0 until blockCount) {
            val blockStart = System.currentTimeMillis()
            var data: ByteArray? = null
            // Только 2 retry: clone-карта после первой_error кладёт CRYPTO1 state
            // навсегда, дальнейшие retry чисто увеличивают время без шансов.
            repeat(2) { attempt ->
                try {
                    ensureConnected(mfc)
                    data = mfc.readBlock(base + b)
                    return@repeat
                } catch (e: Throwable) {
                    val kind = e.javaClass.simpleName
                    val elapsed = System.currentTimeMillis() - blockStart
                    Log.w(TAG, "readBlock sector $sector block ${base + b} attempt $attempt $kind after ${elapsed}ms: ${e.message}")
                    // БЕЗ session reset внутри retry: после первого fail CRYPTO1-session на
                    // clone-карте уже мёртв, mfc.close()+connect() кидает IOException 'null'
                    // на каждый следующий вызов и занимает ~800ms впустую. Просто retry без
                    // reset — следующая attempt либо прочитает (если сессия частично жива),
                    // либо снова упадёт (и тогда ABORT-логика маркирует сектор failed).
                    Thread.sleep(200)
                }
            }
            val blockElapsed = System.currentTimeMillis() - blockStart
            if (data != null) {
                Log.d(TAG, "readBlock sector $sector block ${base + b} OK in ${blockElapsed}ms")
                blocks += data.joinToString(" ") { String.format("%02X", it) }
            } else {
                notes.add("readSectorBlocks sector $sector block ${base + b}: 2 retries exhausted (${blockElapsed}ms)")
                blocks += "(read failed)"
                // НЕ ABORT: clone-карты часто делают fail на одном block в середине, но
                // следующие blocks читаются нормально. Идём дальше — какие прочитаются,
                // те прочитаются; остальные — "(read failed)".
            }
        }
        val sectorElapsed = System.currentTimeMillis() - sectorStart
        val successCount = blocks.count { it != "(read failed)" }
        val label = labelKey(authedKey!!)
        notes.add("readSectorBlocks sector $sector: $label, $successCount/$blockCount OK in ${sectorElapsed}ms")
        Log.i(TAG, "readSectorBlocks sector $sector DONE: $label, $successCount/$blockCount OK in ${sectorElapsed}ms (per-block retry)")
        return SectorRead(blocks, label)
    }

    /**
     * Гарантирует, что mfc подключён. После IllegalStateException mfc может быть
     * disconnected (close() после IOException фактически иногда сбрасывает состояние,
     * но иногда оставляет mfc.isConnected=false при видимости connection alive).
     */
    private fun ensureConnected(mfc: MifareClassic) {
        if (!mfc.isConnected) {
            runCatching {
                mfc.connect()
                mfc.timeout = 3000
            }
        }
    }

    /** Re-authenticate сектор с заданным ключом (посже IOException). Best effort. */
    private fun reAuthWith(mfc: MifareClassic, sector: Int, key: ByteArray) {
        try {
            mfc.authenticateSectorWithKeyA(sector, key)
        } catch (_: Exception) {
            try { mfc.authenticateSectorWithKeyB(sector, key) } catch (_: Exception) {}
        }
    }

    /** Probe-read: читаем первый блок сектора без retry, чтобы верифицировать что auth реальный.
     *  На clone-картах mfc.authenticateSectorWithKeyA может вернуть success даже с неверным ключом
     *  (broken Crypto1 эмулятор) — только реальный read подтверждает auth. */
    private fun tryReadFirstBlock(mfc: MifareClassic, sector: Int, base: Int): ByteArray? {
        return try {
            mfc.readBlock(base)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildCandidateKeys(asopKeyMaterial: List<ByteArray>): List<ByteArray> {
        val uniqueKeys = linkedSetOf<String>()
        val result = mutableListOf<ByteArray>()

        for (key in FACTORY_KEYS) {
            val hex = key.joinToString("") { String.format("%02X", it) }
            if (uniqueKeys.add(hex)) result.add(key)
        }

        for (key in asopKeyMaterial) {
            if (key.size < 6) continue
            val keyA = key.copyOfRange(0, 6)
            val keyB = key.copyOfRange(6, 12)
            val hexA = keyA.joinToString("") { String.format("%02X", it) }
            val hexB = keyB.joinToString("") { String.format("%02X", it) }
            if (uniqueKeys.add(hexA)) result.add(keyA)
            if (uniqueKeys.add(hexB)) result.add(keyB)
        }

        // Дополнительно: NULL keyA = 00 00 00 00 00 00 (часто встречается на недописаных
        // clone-картах или если writeIdentity не записал KeyA но записал KeyB).
        // И "all-FF" вариант. Эти кандидаты сильно помогают когда writeIdentity был
        // прерван на середине.
        val nullKey = byteArrayOf(0, 0, 0, 0, 0, 0)
        val allFF = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        if (uniqueKeys.add("000000000000")) result.add(nullKey)
        if (uniqueKeys.add("FFFFFFFFFFFF")) result.add(allFF)

        return result
    }

    private fun errorResult(tag: Tag, message: String): ReadResult {
        val uid = tag.id.joinToString("") { String.format("%02X", it) }
        return ReadResult(
            techs = listOfNotNull("MifareClassic", NfcA.get(tag)?.tag?.techList?.joinToString(", ")),
            uid = uid,
            atqa = null,
            sak = null,
            atsHistorical = null,
            atsHiLayer = null,
            version = null,
            freeMemory = null,
            applications = emptyList(),
            ev2Plus = null,
            nonGenuineReasons = emptyList(),
            notes = listOf(message),
            error = message,
            isClassic = false,
            classicInfo = null
        )
    }
}

/**
 * Промпт 008: декодировать raw-блоки sector 1 в человекочитаемый summary.
 * Используется в CardReadScreen чтобы пользователь мог прочитать
 * содержимое карты без понимания hex:
 *  • magic: VCM1 / SAC1 / BLANK / FACTORY_FF / CUSTOM
 *  • bitmask: hex + список ролей (CARRIER_ADMIN, DRIVER, …)
 *  • cardId: 8-4-4-4-12 UUID v7 (или "(blank)")
 *  • entityUuid: расшифровка 16 байт → userId/regionId/... или PASSENGER_ANONYMOUS
 */
fun decodeSectorOne(blocks: Map<Int, List<String>>): SectorOneDecode? {
    val s1 = blocks[1] ?: return null
    if (s1.size < 3) return null
    val b0Hex = s1[0]; val b1Hex = s1[1]; val b2Hex = s1[2]
    if (b0Hex == "(read failed)" || b1Hex == "(read failed)" || b2Hex == "(read failed)") return null
    return try {
        val b0 = hexToBytesPublic(b0Hex)
        val b1 = hexToBytesPublic(b1Hex)
        val b2 = hexToBytesPublic(b2Hex)
        val magic = when {
            b0.size >= 4 && b0.copyOfRange(0, 4).contentEquals("VCM1".toByteArray(Charsets.US_ASCII)) -> "VCM1"
            b0.size >= 4 && b0.copyOfRange(0, 4).contentEquals("SAC1".toByteArray(Charsets.US_ASCII)) -> "SAC1"
            b0.size >= 4 && b0.all { it == 0x00.toByte() } -> "BLANK"
            b0.size >= 4 && b0.all { it == 0xFF.toByte() } -> "FACTORY_FF"
            else -> "CUSTOM"
        }
        val bitmask: Int = if (b0.size >= 6) {
            (b0[4].toInt() and 0xFF) or ((b0[5].toInt() and 0xFF) shl 8)
        } else 0
        val cardIdFmt = formatUuid(b1)
        val entityAllZero = b2.all { it == 0x00.toByte() }
        val entityFmt = if (entityAllZero) "(пусто — passenger anonymous)"
            else formatUuid(b2)
        val bitmaskRoles: List<String> = ru.asop.terminal.activation.AsopCardType
            .allRolesForBitmask(bitmask and 0x3FFF)
            .map { "${it.ordinal}=${it.label} (${it.role})" }
        SectorOneDecode(
            magic = magic,
            bitmaskHex = String.format("0x%04X", bitmask),
            bitmaskValue = bitmask,
            roles = bitmaskRoles,
            cardIdRaw = bytesToUuid(b1),
            cardIdFormatted = cardIdFmt,
            cardIdPresent = !b1.all { it == 0x00.toByte() },
            entityRaw = bytesToUuid(b2),
            entityFormatted = entityFmt,
            entityPresent = !entityAllZero
        )
    } catch (_: Exception) { null }
}

private val SECTOR_HEX_RE = Regex("[^0-9A-Fa-f]")

/** public-variant: не делает strict-format throw — выдаёт null-byte-padded. */
private fun hexToBytesPublic(hex: String): ByteArray {
    val cleaned = SECTOR_HEX_RE.replace(hex, "")
    val out = ByteArray(16)
    for (i in 0 until 16) {
        if (i * 2 + 1 < cleaned.length) {
            val hi = cleaned[i * 2].digitToIntOrNull(16) ?: 0
            val lo = cleaned[i * 2 + 1].digitToIntOrNull(16) ?: 0
            out[i] = ((hi shl 4) or lo).toByte()
        } else {
            out[i] = 0
        }
    }
    return out
}

/** 16 байт → UUIDv7-строка (8-4-4-4-12). Если все нули → "(blank)". */
fun formatUuid(bytes: ByteArray): String = if (bytes.all { it == 0.toByte() }) "(blank)" else bytesToUuid(bytes)

private fun bytesToUuid(bytes: ByteArray): String {
    if (bytes.size < 16) return "(short)"
    val sb = StringBuilder()
    for (i in 0 until 16) {
        sb.append(String.format("%02X", bytes[i].toInt() and 0xFF))
        if (i == 3 || i == 5 || i == 7 || i == 9) sb.append('-')
    }
    return sb.toString()
}

data class SectorOneDecode(
    val magic: String,
    val bitmaskHex: String,
    val bitmaskValue: Int,
    val roles: List<String>,
    val cardIdRaw: String,
    val cardIdFormatted: String,
    val cardIdPresent: Boolean,
    val entityRaw: String,
    val entityFormatted: String,
    val entityPresent: Boolean
)
