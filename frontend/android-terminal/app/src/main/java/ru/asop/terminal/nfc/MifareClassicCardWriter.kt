package ru.asop.terminal.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.util.Log
import ru.asop.proto.v1.CardIdentity as ProtoCardIdentity
import ru.asop.terminal.activation.CardIdentityVcm1
import ru.asop.terminal.activation.EntityType
import java.io.IOException
import java.util.UUID

/**
 * Программирование MIFARE Classic (1K/4K) для активации карт АСОП (промпт 007).
 *
 * В отличие от DESFire, MIFARE Classic не имеет приложений/файлов — данные
 * пишутся прямо в 16-байтные блоки, а секторный трейлер (последний блок
 * сектора) содержит KeyA(6)|AccessBits(4)|KeyB(6).
 *
 * Используем Android tech API `MifareClassic`, а не raw NfcA, — он проводит
 * CRYPTO1-рукопожатие и инкапсулирует команду протокола.
 *
 * ## Формат payload на карте (SAC1)
 * ```
 * Magic "SAC1" (4 ASCII) | version (1, =0x01) | lenProto (2, LE) | lenSig (2, LE =256)
 * | protoBytes (CardIdentity proto serialized) | signatureRaw (256 bytes)
 * ```
 * Линейная упаковка в 16-байтные блоки данных identity-области (секторы 1..15);
 * хвост добивается незначащими байтами. Конец определяется по `lenProto + lenSig`.
 *
 * ## Identity-область и access bits
 * Секторы 1..15 (4 блока в каждом: 3 data + 1 trailer). Сектор 0 не трогаем
 * (manufacturer: UID+BCC). Каждый сектор мы сначала auth factory-ключами,
 * затем записываем trailer с новыми KeyA/KeyB (access bits `FF 07 80 69`),
 * после чего пишем data-блоки reinstall payload.
 *
 * После записи: factory auth на этом секторе не проходит → индикатор успешной смены ключей.
 */
class MifareClassicCardWriter {

    data class WriteResult(
        val ok: Boolean,
        val steps: List<String>,
        val error: String?
    )

    /** Состояние карты, определённое по auth sector 1. */
    enum class ClassicState { NEW, EXISTING, UNRECOGNIZED }

    /** Дешифрованный SAC1 payload: (protoBytes, signatureRaw). */
    data class Sac1Payload(val protoBytes: ByteArray, val signatureRaw: ByteArray)

    companion object {
        private const val TAG = "MifareClassicCardWriter"

        /** Sector 1 — маркер новой/existing карты (первый сектор identity-области). */
        private const val FIRST_IDENTITY_SECTOR = 1

        /** Последний сектор identity-области (для 1K: 15, для 4K: 15 — sector 16+ не задействуем). */
        private const val LAST_IDENTITY_SECTOR = 15

        /** Access bits (transport-профиль): bytes 6..9 trailer = `FF 07 80 69`. */
        val ACCESS_BITS: ByteArray = byteArrayOf(
            0xFF.toByte(), 0x07, 0x80.toByte(), 0x69
        )

        /** SAC1 magic bytes: "SAC1" ASCII. */
        val SAC1_MAGIC: ByteArray = byteArrayOf(
            'S'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte()
        )

        /** VCM1 magic bytes: "VCM1" ASCII (промпт 008). */
        val VCM1_MAGIC: ByteArray = byteArrayOf(
            'V'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte()
        )

        /** Версия payload (1 байт). */
        const val SAC1_VERSION: Byte = 0x01

        /** Размер подписи RSA-PSS-SHA256 (256 байт для RSA-2048 ключа). */
        const val SIGNATURE_LENGTH = 256

        /** Размер блока Classic. */
        const val BLOCK_SIZE = 16

        /** VCM1 первый используемый блок в sector 1. */
        const val FIRST_IDENTITY_BLOCK = 4       // mfc.sectorToBlock(1) = 4

        /** Заводские ключи (MIFARE Classic transport-профиль, см. спецификацию). */
        val FACTORY_KEYS: List<ByteArray> = listOf(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
            byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte())
        )

        /** NULL keyA (промпт 008: writeVcm1 пишет keyA = ZERO если карта была ZERO-keyA). */
        val NULL_KEY_A: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0)

        /**
         * Сборка SAC1-payload из proto bytes и подписи.
         *
         * @param protoBytes сериализованный proto CardIdentity
         * @param signatureRaw бинарная RSA-PSS подпись (256 байт)
         */
        fun buildSac1Payload(protoBytes: ByteArray, signatureRaw: ByteArray): ByteArray {
            require(protoBytes.size in 1..0xFFFF) { "protoBytes.length out of range: ${protoBytes.size}" }
            require(signatureRaw.size in 1..0xFFFF) { "signatureRaw.length out of range: ${signatureRaw.size}" }
            val out = ByteArray(4 + 1 + 2 + 2 + protoBytes.size + signatureRaw.size)
            System.arraycopy(SAC1_MAGIC, 0, out, 0, 4)
            out[4] = SAC1_VERSION
            out[5] = (protoBytes.size and 0xFF).toByte()
            out[6] = ((protoBytes.size shr 8) and 0xFF).toByte()
            out[7] = (signatureRaw.size and 0xFF).toByte()
            out[8] = ((signatureRaw.size shr 8) and 0xFF).toByte()
            System.arraycopy(protoBytes, 0, out, 9, protoBytes.size)
            System.arraycopy(signatureRaw, 0, out, 9 + protoBytes.size, signatureRaw.size)
            return out
        }

        /** Разбор SAC1-payload обратно в proto bytes + подпись. */
        fun parseSac1Payload(buf: ByteArray): Sac1Payload? {
            if (buf.size < 9) return null
            if (!buf.copyOfRange(0, 4).contentEquals(SAC1_MAGIC)) return null
            if (buf[4] != SAC1_VERSION) return null
            val lenProto = (buf[5].toInt() and 0xFF) or ((buf[6].toInt() and 0xFF) shl 8)
            val lenSig = (buf[7].toInt() and 0xFF) or ((buf[8].toInt() and 0xFF) shl 8)
            if (lenProto == 0 || lenSig == 0) return null
            if (9 + lenProto + lenSig > buf.size) return null
            val proto = buf.copyOfRange(9, 9 + lenProto)
            val sig = buf.copyOfRange(9 + lenProto, 9 + lenProto + lenSig)
            return Sac1Payload(proto, sig)
        }
    }

    // ---------- detectState ----------

    /**
     * Результат detectState — состояние карты и какой именно ключ смог авторизоваться
     * в sector 1. Это критично для reflash: нужно использовать ИМЕННО ТОТ ключ, что
     * записан на карте, иначе reflash не сможет auth и тратить время впустую.
     *
     * - matchedKey = null → factory auth прошёл (карта NEW). workingKeyClassicA=null.
     * - matchedKey = ByteArray(24) → какой-то ASOP-ключ auth ОК. workingKeyClassicA=matchedKey[0..5].
     * - matchedKey = ZERO_KEY_BYTE_ARRAY (6 нулей) → ключA на карте NULL (если writeIdentity
     *   был с ZERO_KEY, или если кто-то переписал KeyA=0).
     */
    data class DetectResult(
        val state: ClassicState,
        val matchedKey: ByteArray?,    // 24-byte ASOP-ключ, либо null=NEW, либо 6-zeros=NULL-keyA
        val matchedKind: MatchKind     // для UI-логирования
    ) {
        enum class MatchKind { FACTORY, ASOP_KEYA, ASOP_KEYB, NULL_KEYA, UNKNOWN }
    }

    /**
     * Определяет состояние карты: NEW / EXISTING / UNRECOGNIZED + какой ключ matched.
     * - factory auth прошёл → NEW, matchedKey=null, matchedKind=FACTORY;
     * - NULL keyA `00 00 00 00 00 00` matched → EXISTING, matchedKey=6-zeros, matchedKind=NULL_KEYA;
     * - ASOP-ключ из candidateKeys matched → EXISTING, matchedKey=24-байта ASOP, kind=KEYA/KEYB;
     * - иначе UNRECOGNIZED, matchedKey=null.
     */
    fun detectState(tag: Tag, candidateKeys: List<ByteArray>): DetectResult {
        val mfc = MifareClassic.get(tag) ?: return DetectResult(ClassicState.UNRECOGNIZED, null, DetectResult.MatchKind.UNKNOWN)
        return try {
            mfc.connect()
            mfc.timeout = 3000
            // 1. Factory — если прошёл, карта нетронутая
            for (factory in FACTORY_KEYS) {
                if (tryAuth(mfc, FIRST_IDENTITY_SECTOR, factory)) {
                    Log.d(TAG, "detectState: factory auth OK on sector $FIRST_IDENTITY_SECTOR → NEW")
                    return DetectResult(ClassicState.NEW, null, DetectResult.MatchKind.FACTORY)
                }
            }
            // 2. NULL keyA — часто встречается на clone-картах после writeIdentity с ZERO_KEY
            val nullKey = byteArrayOf(0, 0, 0, 0, 0, 0)
            if (tryAuth(mfc, FIRST_IDENTITY_SECTOR, nullKey)) {
                Log.d(TAG, "detectState: NULL keyA auth OK → EXISTING with NULL keyA")
                return DetectResult(ClassicState.EXISTING, nullKey, DetectResult.MatchKind.NULL_KEYA)
            }
            // 3. ASOP-ключи
            for (key in candidateKeys) {
                if (key.size < 12) continue
                if (tryAuth(mfc, FIRST_IDENTITY_SECTOR, key.copyOfRange(0, 6))) {
                    Log.d(TAG, "detectState: ASOP KeyA auth OK on sector $FIRST_IDENTITY_SECTOR → EXISTING")
                    return DetectResult(ClassicState.EXISTING, key, DetectResult.MatchKind.ASOP_KEYA)
                }
                if (tryAuth(mfc, FIRST_IDENTITY_SECTOR, key.copyOfRange(6, 12))) {
                    Log.d(TAG, "detectState: ASOP KeyB auth OK on sector $FIRST_IDENTITY_SECTOR → EXISTING")
                    return DetectResult(ClassicState.EXISTING, key, DetectResult.MatchKind.ASOP_KEYB)
                }
            }
            DetectResult(ClassicState.UNRECOGNIZED, null, DetectResult.MatchKind.UNKNOWN)
        } catch (e: Exception) {
            Log.w(TAG, "detectState: ${e.message}")
            DetectResult(ClassicState.UNRECOGNIZED, null, DetectResult.MatchKind.UNKNOWN)
        } finally {
            runCatching { mfc.close() }
        }
    }

    // ---------- readStream ----------

    /**
     * Читает SAC1-payload: auth sector 1 candidate-ключами → читает блоки data
     * секторов 1..15, пока не наберёт `lenProto + lenSig` из SAC1.
     * @return null если auth/parse не удался.
     */
    fun readStream(tag: Tag, candidateKeys: List<ByteArray>): Sac1Payload? {
        val mfc = MifareClassic.get(tag) ?: return null
        return try {
            mfc.connect()
            mfc.timeout = 3000
            // Auth сначала ASOP-ключами, потом factory (если карта была NEW, но мы хотим read)
            var authed = false
            for (key in candidateKeys) {
                if (key.size < 12) continue
                if (tryAuth(mfc, FIRST_IDENTITY_SECTOR, key.copyOfRange(0, 6)) ||
                    tryAuth(mfc, FIRST_IDENTITY_SECTOR, key.copyOfRange(6, 12))
                ) {
                    authed = true
                    break
                }
            }
            if (!authed) {
                for (factory in FACTORY_KEYS) {
                    if (tryAuth(mfc, FIRST_IDENTITY_SECTOR, factory)) {
                        authed = true
                        break
                    }
                }
            }
            if (!authed) return null

            // Читаем блоки секторов 1..15 (data-блоки, последний блок — trailer — пропускаем).
            val collected = ByteArray(0)
            var collectedBytes = mutableListOf<Byte>()
            outer@ for (sector in FIRST_IDENTITY_SECTOR..LAST_IDENTITY_SECTOR) {
                val blockCount = mfc.getBlockCountInSector(sector)
                val base = mfc.sectorToBlock(sector)
                // Пропускаем последний блок (trailer); но auth держится только для текущего сектора.
                for (blockIdx in 0 until blockCount - 1) {
                    try {
                        val block = mfc.readBlock(base + blockIdx)
                        collectedBytes.addAll(block.toList())
                    } catch (e: IOException) {
                        Log.w(TAG, "readStream: block ${base + blockIdx} lost: ${e.message}")
                        return null
                    }
                    // Проверка на завершение: SAC1 header виден?
                    val buf = collectedBytes.toByteArray()
                    if (buf.size >= 9 && buf.copyOfRange(0, 4).contentEquals(SAC1_MAGIC)) {
                        val lenProto = (buf[5].toInt() and 0xFF) or ((buf[6].toInt() and 0xFF) shl 8)
                        val lenSig = (buf[7].toInt() and 0xFF) or ((buf[8].toInt() and 0xFF) shl 8)
                        if (lenProto > 0 && lenSig > 0 && buf.size >= 9 + lenProto + lenSig) {
                            break@outer
                        }
                    }
                }
                // Перейти к следующему сектору — нужен его auth (ключ тот же).
                // NB: раньше здесь был `mfc.readBlock(trailer следующего сектора)` для
                // извлечения KeyB — это читало trailer БЕЗ auth следующего сектора и
                // всегда падало с IOException на защищённых картах (readStream терял
                // сессию). Теперь просто пробуем те же candidate-ключи.
                if (sector < LAST_IDENTITY_SECTOR) {
                    var ok = false
                    for (key in candidateKeys) {
                        if (key.size < 12) continue
                        if (tryAuth(mfc, sector + 1, key.copyOfRange(0, 6)) ||
                            tryAuth(mfc, sector + 1, key.copyOfRange(6, 12))
                        ) { ok = true; break }
                    }
                    if (!ok) {
                        for (factory in FACTORY_KEYS) {
                            if (tryAuth(mfc, sector + 1, factory)) { ok = true; break }
                        }
                    }
                    if (!ok) {
                        Log.w(TAG, "readStream: auth lost on sector ${sector + 1}")
                        return null
                    }
                }
            }

            val buf = collectedBytes.toByteArray()
            parseSac1Payload(buf)
        } catch (e: Exception) {
            Log.w(TAG, "readStream: ${e.message}")
            null
        } finally {
            runCatching { mfc.close() }
        }
    }

    // ---------- newCard ----------

    /**
     * Прошивка новой карты: factory auth на каждом секторе 1..15 →
     * запись trailer + data-блоков → read-back verification.
     *
     * @param payload SAC1-payload (proto + sig, 4+1+2+2+protoLen+sigLen байт).
     *                Должен быть выровнен до длины, кратной 16 (см. `padPayload`).
     * @param keyA новый 6-байтный Key A для всех секторов identity-области.
     * @param keyB новый 6-байтный Key B (может быть пустым — чтение/запись через KeyA).
     */
    fun newCard(tag: Tag, payload: ByteArray, keyA: ByteArray, keyB: ByteArray): WriteResult {
        require(payload.size % BLOCK_SIZE == 0) {
            "payload должен быть выровнен до ${BLOCK_SIZE} (текущий: ${payload.size})"
        }
        require(keyA.size == 6) { "keyA должен быть 6 байт" }
        require(keyB.size == 6) { "keyB должен быть 6 байт" }

        // Multi-pass с hard-reset NFC-сессии для clone-карт (где auth session утекает).
        return multiPassWrite(tag) { mfc ->
            writeIdentityInternal(mfc, payload, FACTORY_KEYS, keyA, keyB, isExisting = false)
        }.also { res ->
            if (res.error == null) return@also
            // final error reporting
        }
    }

    /**
     * Общая multi-pass обёртка с hard-reset NFC-сессии: каждый pass делает
     * `NfcA.close+connect` и заново получает `MifareClassic.get(tag)`. На clone-картах
     * Classic между двумя командами сессия утекает, и этот механизм даёт ей
     * полностью пересозданный канал. Используется из `newCard()` и `reflash()`.
     */
    private fun multiPassWrite(
        tag: Tag,
        op: (MifareClassic) -> Triple<Boolean, List<String>, String?>,
    ): WriteResult {
        val combinedSteps = mutableListOf<String>()
        repeat(3) { attempt ->
            Log.d(TAG, "multiPassWrite: pass $attempt/3 starting (hard-reset MifareClassic)")
            val mfc = MifareClassic.get(tag) ?: return multiPassResult(combinedSteps,
                "MifareClassic недоступен (карта не Classic?)")
            try {
                // Hard-reset: закрываем и переоткрываем mfc. Android TagTech заново открывает
                // underlying NfcA connection сама (это даёт клону свежую сессию для CRYPTO1 auth).
                runCatching { if (mfc.isConnected) mfc.close() }
                Thread.sleep(120)
                mfc.connect()
                mfc.timeout = 5000
                val (ok, attSteps, err) = op(mfc)
                combinedSteps.addAll(attSteps)
                if (ok) {
                    Log.d(TAG, "multiPassWrite: pass $attempt OK")
                    runCatching { if (mfc.isConnected) mfc.close() }
                    return WriteResult(true, combinedSteps, null)
                }
                if (err?.contains("auth сектор") == true || err?.contains("Tag was lost") == true) {
                    combinedSteps += "pass $attempt auth FAIL — sleeping 800ms"
                    Log.w(TAG, "multiPassWrite: pass $attempt auth FAIL — sleeping 800ms")
                    Thread.sleep(800)
                } else {
                    runCatching { if (mfc.isConnected) mfc.close() }
                    return multiPassResult(combinedSteps, err)
                }
            } catch (e: Exception) {
                combinedSteps += "pass $attempt exception: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "multiPassWrite: pass $attempt exception: ${e.message}")
                runCatching { if (mfc.isConnected) mfc.close() }
                Thread.sleep(800)
            }
        }
        return multiPassResult(combinedSteps,
            "auth сектор 1 не прошёл за 3 попытки (классическая клон-карта теряет сессию между detect и write; убедитесь что карта стабильно лежит на NFC антенне)")
    }

    private fun multiPassResult(steps: List<String>, err: String?): WriteResult =
        WriteResult(false, steps, err ?: "unknown")

    // ---------- reflash ----------

    /**
     * Перерегистрация existing карты: ASOP auth (KeyA или KeyB) → перезапись data-блоков,
     * trailer остаётся прежним. Используем те же KeyA/KeyB, что и раньше.
     */
    fun reflash(tag: Tag, payload: ByteArray, workingKeyA: ByteArray, workingKeyB: ByteArray): WriteResult {
        require(payload.size % BLOCK_SIZE == 0) {
            "payload должен быть выровнен до ${BLOCK_SIZE} (текущий: ${payload.size})"
        }
        require(workingKeyA.size == 6) { "workingKeyA должен быть 6 байт" }
        require(workingKeyB.size == 6) { "workingKeyB должен быть 6 байт" }
        // Mixed-state reflash: isExisting=false, чтобы per-sector через `usedAsopKey` решать,
        // нужен ли write trailer. Sector 1 (ASOP keyA OK) — skip trailer.
        // Sectors 2..15 (factory OK) — write trailer с ASOP key (безопасность!).
        return multiPassWrite(tag) { mfc ->
            val asopKeys = listOf(workingKeyA, workingKeyB)
            writeIdentityInternal(mfc, payload, asopKeys, workingKeyA, workingKeyB, isExisting = false)
        }
    }

    // ---------- writeVcm1 (промпт 008) ----------

    /**
     * Прошивка VCM1-identity на MIFARE Classic: пишет **только sector 1 (3 data-блока
     * + trailer)**. Sector 2 и sectors 3-4 NO-OP (резерв на будущее).
     *
     * @param vcm1 готовая VCM1-структура (cardId, bitmask, entity либо null).
     * @param keyA новый 6-байтный KeyA (записывается в trailer).
     * @param keyB новый 6-байтный KeyB (записывается в trailer).
     * @param isExisting true если карта уже активирована (тогда trailer не переписываем).
     * @param workingKeyA старая KeyA если `isExisting=true` (auth для чтений).
     * @param workingKeyB старая KeyB если `isExisting=true`.
     */
    fun writeVcm1(
        tag: Tag,
        vcm1: CardIdentityVcm1,
        keyA: ByteArray,
        keyB: ByteArray,
        isExisting: Boolean = false,
        workingKeyA: ByteArray = keyA,
        workingKeyB: ByteArray = keyB
    ): WriteResult {
        require(keyA.size == 6) { "keyA должен быть 6 байт" }
        require(keyB.size == 6) { "keyB должен быть 6 байт" }
        require(workingKeyA.size == 6) { "workingKeyA должен быть 6 байт" }
        require(workingKeyB.size == 6) { "workingKeyB должен быть 6 байт" }
        val payload = vcm1.encodeAsBytes()
        require(payload.size == CardIdentityVcm1.TOTAL_BYTES) {
            "VCM1 payload size mismatch: ${payload.size} (expected ${CardIdentityVcm1.TOTAL_BYTES})"
        }

        Log.d(TAG, "writeVcm1: starting — isExisting=$isExisting, bitmask=0x${vcm1.bitmask.toString(16)}, " +
                "cardId=${vcm1.cardId}, entity=${vcm1.entity}")

        return multiPassWrite(tag) { mfc ->
            val authCandidates: List<ByteArray> = if (isExisting) {
                listOf(workingKeyA, workingKeyB, NULL_KEY_A) + FACTORY_KEYS
            } else {
                FACTORY_KEYS + listOf(NULL_KEY_A) + listOf(workingKeyA, workingKeyB)
            }
            writeVcm1Internal(mfc, payload, authCandidates, keyA, keyB, isExisting, workingKeyA, workingKeyB)
        }
    }

    /**
     * Пишет 3 data-блока + trailer для sector 1. Простой flow без multi-sector
     * итерации — VCM1 помещается в один sector.
     */
    private fun writeVcm1Internal(
        mfc: MifareClassic,
        payload: ByteArray,         // 48 bytes VCM1
        authKeys: List<ByteArray>,
        keyA: ByteArray,
        keyB: ByteArray,
        isExisting: Boolean,
        workingKeyA: ByteArray = keyA,
        workingKeyB: ByteArray = keyB
    ): Triple<Boolean, List<String>, String?> {
        val steps = mutableListOf<String>()
        val sector = FIRST_IDENTITY_SECTOR
        val blockCount = mfc.getBlockCountInSector(sector)
        val base = mfc.sectorToBlock(sector)
        val trailerIdx = base + blockCount - 1

        // 1. Auth sector 1
        var authed = false
        // Карта уже активирована и auth прошёл НАШИМ рабочим ключом → trailer уже содержит
        // наши ключи, перезаписывать не нужно. Если auth прошёл factory/NULL-key — карта
        // была не защищена → обязательно пишем trailer (защита).
        var usedExistingKey = false
        for (k in authKeys) {
            if (tryAuth(mfc, sector, k)) {
                authed = true
                usedExistingKey = isExisting && (k.contentEquals(workingKeyA) || k.contentEquals(workingKeyB))
                break
            }
        }
        if (!authed) {
            Log.w(TAG, "writeVcm1: auth sector $sector FAIL (no keys worked)")
            steps += "auth sector $sector FAIL"
            return Triple(false, steps, "auth sector $sector не прошёл ни одним ключом")
        }
        steps += "auth sector $sector OK"
        Log.d(TAG, "writeVcm1: auth OK")

        // 2. Write data-блоки (block 0, 1, 2)
        for (dataIdx in 0 until CardIdentityVcm1.USED_BLOCK_COUNT) {
            val src = payload.copyOfRange(dataIdx * BLOCK_SIZE, (dataIdx + 1) * BLOCK_SIZE)
            val blockIdx = base + dataIdx
            try {
                mfc.writeBlock(blockIdx, src)
            } catch (e: IOException) {
                Log.w(TAG, "writeVcm1: writeBlock $blockIdx IOException: ${e.message}")
                return Triple(false, steps, "writeBlock $blockIdx IOException: ${e.message}")
            }
            // Verify read-back: block 0..2
            val readBack = try { mfc.readBlock(blockIdx) } catch (e: IOException) { null }
            if (readBack == null || !readBack.contentEquals(src)) {
                Log.w(TAG, "writeVcm1: block $blockIdx read-back mismatch")
                return Triple(false, steps, "read-back block $blockIdx mismatch (clone likely)")
            }
        }
        steps += "VCM1 3 data-блока записано и verified"

        // 3. Write trailer — если карта была НЕ защищена нашим ключом (factory/NULL auth):
        //    пишем наши ключи, чтобы защитить sector 1. Если auth прошёл РАБОЧИМ ключом
        //    (карта уже наша, trailer уже содержит те же ключи) — skip.
        if (!usedExistingKey) {
            val trailer = ByteArray(BLOCK_SIZE)
            System.arraycopy(keyA, 0, trailer, 0, 6)
            System.arraycopy(ACCESS_BITS, 0, trailer, 6, 4)
            System.arraycopy(keyB, 0, trailer, 10, 6)
            try {
                mfc.writeBlock(trailerIdx, trailer)
            } catch (e: IOException) {
                Log.w(TAG, "writeVcm1: trailer writeBlock IOException: ${e.message}")
                return Triple(false, steps, "writeBlock trailer IOException: ${e.message}")
            }
            Thread.sleep(80)
            // Verify: reauth с НОВЫМ KeyA должна пройти (=ключи сменились).
            if (!tryAuth(mfc, sector, keyA)) {
                Log.w(TAG, "writeVcm1: reauth newKey FAIL — trailer not committed")
                return Triple(false, steps, "reauth newKey FAIL (клон-карта не подтвердила trailer)")
            }
            steps += "trailer written + reauth OK newKey"
            Log.d(TAG, "writeVcm1: trailer written, reauth OK newKey")
        } else {
            steps += "trailer skipped (карта уже защищена нашим ключом)"
            Log.d(TAG, "writeVcm1: skipping trailer (card already has ASOP trailer)")
        }

        steps += "VCM1 OK (cardId=${payload.takeLastUUID()}, bitmask=0x${payload.bitmaskLEtoHex()})"
        return Triple(true, steps, null)
    }

    // ---------- readVcm1 (промпт 008) ----------

    /**
     * Чтение VCM1-identity с MIFARE Classic карты (только sector 1).
     * Auth+probe verification для clone-эмуляторов (broken CRYPTO1): probe-read после auth
     * проверяет, что ключ действительно подходит.
     */
    fun readVcm1(tag: Tag, candidateKeys: List<ByteArray>): CardIdentityVcm1? {
        val mfc = MifareClassic.get(tag) ?: return null
        return try {
            mfc.connect()
            mfc.timeout = 3000
            val sector = FIRST_IDENTITY_SECTOR
            val blockCount = mfc.getBlockCountInSector(sector)
            val base = mfc.sectorToBlock(sector)

            // Промпт 008: ASOP-ключ приходит в `terminal_keys` как 24-байтный 3K3DES
            // (keyMaterial[0..5]=KeyA, [6..11]=KeyB, [12..23]=maybe-padding).
            // Для MifareClassic нужны 6-байтные KeyA/KeyB. Нормализуем candidates.
            val sixByteCandidates: List<Pair<ByteArray, String>> = candidateKeys.flatMap { full ->
                if (full.size == 6) listOf(full to "ASOP-6")
                else if (full.size >= 12) listOf(
                    full.copyOfRange(0, 6) to "ASOP-KeyA[${full.size}B]",
                    full.copyOfRange(6, 12) to "ASOP-KeyB[${full.size}B]"
                )
                else emptyList()
            }
            Log.i(TAG, "readVcm1: candidates=${sixByteCandidates.size} (after split from candidates.size=${candidateKeys.size})")

            // Auth + probe-read (только первый блок через tryReadFirstBlock)
            var authedKey: ByteArray? = null
            var attempts = 0
            for ((key, label) in sixByteCandidates) {
                attempts++
                var authed = false
                try { mfc.authenticateSectorWithKeyA(sector, key); authed = true } catch (e: Exception) {
                    Log.w(TAG, "readVcm1: KeyA auth exception $label ${key.toHexShort()}: ${e.message}")
                }
                if (!authed) continue
                val probe = tryReadFirstBlock(mfc, sector, base)
                if (probe != null) {
                    authedKey = key
                    Log.i(TAG, "readVcm1: auth+verify OK with $label ${key.toHexShort()} (attempt #$attempts)")
                    break
                } else {
                    Log.w(TAG, "readVcm1: KeyA auth OK but probe-read failed $label ${key.toHexShort()}")
                }
            }
            if (authedKey == null) {
                // Попробуем KeyB
                for ((key, label) in sixByteCandidates) {
                    attempts++
                    var authed = false
                    try { mfc.authenticateSectorWithKeyB(sector, key); authed = true } catch (e: Exception) {
                        Log.w(TAG, "readVcm1: KeyB auth exception $label ${key.toHexShort()}: ${e.message}")
                    }
                    if (!authed) continue
                    val probe = tryReadFirstBlock(mfc, sector, base)
                    if (probe != null) {
                        authedKey = key
                        Log.i(TAG, "readVcm1: auth+verify (KeyB) OK with $label ${key.toHexShort()} (attempt #$attempts)")
                        break
                    } else {
                        Log.w(TAG, "readVcm1: KeyB auth OK but probe-read failed $label ${key.toHexShort()}")
                    }
                }
            }
            if (authedKey == null) {
                Log.w(TAG, "readVcm1: NO key authed after $attempts attempts (candidates=${sixByteCandidates.size})")
                return null
            }

            // Read 3 data-блока: block 0 (magic+bitmask), block 1 (cardId), block 2 (entityUuid)
            val buf = ByteArray(CardIdentityVcm1.TOTAL_BYTES)
            for (dataIdx in 0 until CardIdentityVcm1.USED_BLOCK_COUNT) {
                val data = try { mfc.readBlock(base + dataIdx) } catch (e: IOException) {
                    Log.w(TAG, "readVcm1: readBlock ${base + dataIdx} IOException: ${e.message}")
                    return null
                }
                System.arraycopy(data, 0, buf, dataIdx * BLOCK_SIZE, BLOCK_SIZE)
            }

            val parsed = CardIdentityVcm1.decodeFromBytes(buf)
            if (parsed != null) {
                Log.i(TAG, "readVcm1: VCM1 decoded — bitmask=0x${parsed.bitmask.toString(16)}, " +
                        "cardId=${parsed.cardId}, entity=${parsed.entity}")
            } else {
                Log.w(TAG, "readVcm1: bytes do not match VCM1 magic — might be SAC1 or empty")
            }
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "readVcm1: ${e.message}")
            null
        } finally {
            runCatching { mfc.close() }
        }
    }

    /**
     * Промпт 014: записывает новое количество поездок (tripsLeft) в block 0 сектора 1
     * (байты [10..11] UInt16 LE). НЕ трогает cardId (block 1), entityUuid (block 2),
     * bitmask/magic (block 0 [0..5]) — только счётчик. Возвращает old→new или null при ошибке.
     */
    fun writeTripsLeft(
        tag: Tag,
        candidateKeys: List<ByteArray>,
        newTripsLeft: Int
    ): Pair<Int, Int>? {
        if (newTripsLeft !in 0..0xFFFF) {
            Log.w(TAG, "writeTripsLeft: out of UInt16 range: $newTripsLeft")
            return null
        }
        val mfc = MifareClassic.get(tag) ?: return null
        return try {
            mfc.connect()
            mfc.timeout = 3000
            val sector = FIRST_IDENTITY_SECTOR
            val base = mfc.sectorToBlock(sector)

            val sixByteCandidates: List<ByteArray> = candidateKeys.flatMap { full ->
                if (full.size == 6) listOf(full)
                else if (full.size >= 12) listOf(full.copyOfRange(0, 6), full.copyOfRange(6, 12))
                else emptyList()
            }

            // Auth sector 1 любым известным ключом
            var authedKey: ByteArray? = null
            for (key in sixByteCandidates) {
                try { mfc.authenticateSectorWithKeyA(sector, key); authedKey = key; break } catch (_: Exception) {}
            }
            if (authedKey == null) {
                for (key in sixByteCandidates) {
                    try { mfc.authenticateSectorWithKeyB(sector, key); authedKey = key; break } catch (_: Exception) {}
                }
            }
            if (authedKey == null) {
                Log.w(TAG, "writeTripsLeft: no key authed")
                return null
            }

            // Читаем текущий block 0, парсим tripsLeft из [10..11]
            val block0 = try { mfc.readBlock(base) } catch (e: IOException) {
                Log.w(TAG, "writeTripsLeft: readBlock0 IOException: ${e.message}"); return null
            }
            if (block0.size != BLOCK_SIZE) return null
            val oldTrips = ((block0[CardIdentityVcm1.TRIPS_OFFSET].toInt() and 0xFF)) or
                ((block0[CardIdentityVcm1.TRIPS_OFFSET + 1].toInt() and 0xFF) shl 8)

            // Патчим только [10..11], остальное без изменений
            block0[CardIdentityVcm1.TRIPS_OFFSET] = (newTripsLeft and 0xFF).toByte()
            block0[CardIdentityVcm1.TRIPS_OFFSET + 1] = ((newTripsLeft shr 8) and 0xFF).toByte()

            try { mfc.writeBlock(base, block0) } catch (e: IOException) {
                Log.w(TAG, "writeTripsLeft: writeBlock IOException: ${e.message}"); return null
            }
            // Verify read-back
            val readBack = try { mfc.readBlock(base) } catch (e: IOException) { null }
            if (readBack == null || !readBack.contentEquals(block0)) {
                Log.w(TAG, "writeTripsLeft: read-back mismatch (clone?)")
                return null
            }
            Log.i(TAG, "writeTripsLeft: $oldTrips -> $newTripsLeft OK")
            oldTrips to newTripsLeft
        } catch (e: Exception) {
            Log.w(TAG, "writeTripsLeft error: ${e.message}")
            null
        } finally {
            runCatching { mfc.close() }
        }
    }

    /** Probe-read: читаем первый блок сектора для верификации что auth был реальный. */
    private fun tryReadFirstBlock(mfc: MifareClassic, sector: Int, base: Int): ByteArray? = try {
        mfc.readBlock(base)
    } catch (_: Exception) {
        null
    }

    private fun ByteArray.takeLastUUID(): String = if (size >= 16) {
        val off = size - 16
        val msb = (0..7).fold(0L) { acc, i -> (acc shl 8) or (this[off + i].toLong() and 0xFF) }
        val lsb = (0..7).fold(0L) { acc, i -> (acc shl 8) or (this[off + 8 + i].toLong() and 0xFF) }
        java.util.UUID(msb, lsb).toString()
    } else "?"

    private fun ByteArray.bitmaskLEtoHex(): String =
        if (size >= 6) {
            val low = this[4].toInt() and 0xFF
            val high = (this[5].toInt() and 0xFF) shl 8
            "0x${"%04X".format(low or high)}"
        } else "?"

    // ---------- внутренний flow ----------

    private fun writeWithRetry(
        tag: Tag,
        retryOnFailure: Boolean = true,
        op: (MifareClassic) -> Triple<Boolean, List<String>, String?>
    ): WriteResult {
        val steps = mutableListOf<String>()
        for (attempt in 1..3) {
            val mfc = MifareClassic.get(tag)
            if (mfc == null) {
                Log.w(TAG, "writeWithRetry: MifareClassic.get(tag) == null")
                return WriteResult(false, steps, "MifareClassic недоступен (карта не Classic?)")
            }
            try {
                Log.d(TAG, "writeWithRetry: attempt $attempt/3 starting (mfc.connected=$mfc.isConnected)")
                val (ok, attSteps, err) = op(mfc)
                steps.addAll(attSteps)
                if (ok) {
                    Log.d(TAG, "writeWithRetry: attempt $attempt/3 OK")
                    return WriteResult(true, steps, null)
                }
                val isTransient = err?.contains("Tag was lost") == true
                    || err?.contains("IOException") == true
                    || err?.contains("auth сектор") == true
                if (retryOnFailure && isTransient) {
                    Log.w(TAG, "writeWithRetry: attempt $attempt transient fail: $err, retrying…")
                    steps += "retry $attempt/3"
                    Thread.sleep(400)
                    continue
                }
                Log.w(TAG, "writeWithRetry: attempt $attempt definitive fail: $err")
                return WriteResult(false, steps, err)
            } catch (e: IOException) {
                Log.w(TAG, "writeWithRetry: attempt $attempt IOException: ${e.message}")
                steps += "attempt $attempt IOException: ${e.message}"
                if (attempt < 3) {
                    Thread.sleep(400)
                    continue
                }
                return WriteResult(false, steps, "IOException: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "writeWithRetry: attempt $attempt exception: ${e.javaClass.simpleName}: ${e.message}")
                return WriteResult(false, steps, "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { if (mfc.isConnected) mfc.close() }
            }
        }
        return WriteResult(false, steps, "Не удалось прошить карту за 3 попытки")
    }

    /**
     * @param mfc подключённый MifareClassic
     * @param payload SAC1-payload, уже выровненный по `BLOCK_SIZE`
     * @param authKeys кандидаты для auth (factory для NEW, ASOP для existing)
     * @param keyA новый Key A для trailer (для NEW)
     * @param keyB новый Key B для trailer (для NEW)
     * @param isExisting true если карта уже была активирована (тогда trailer не трогаем)
     */
    private fun writeIdentityInternal(
        mfc: MifareClassic,
        payload: ByteArray,
        authKeys: List<ByteArray>,
        keyA: ByteArray,
        keyB: ByteArray,
        isExisting: Boolean
    ): Triple<Boolean, List<String>, String?> {
        val steps = mutableListOf<String>()
        var dataBlockIdx = 0

        for (sector in FIRST_IDENTITY_SECTOR..LAST_IDENTITY_SECTOR) {
            Log.d(TAG, "newCard: sector $sector/${LAST_IDENTITY_SECTOR} starting")
            // Auth сектора. Сначала пробуем authKeys (factory для NEW, ASOP для EXISTING);
            // если они не подошли — пробуем второй список (ASOP для newCard, factory для reflash).
            // Это покрывает partial-write state: sector 1 уже наш ASOP (trailer был записан),
            // sectorы 2..15 ещё factory.
            var authed = false
            for (k in authKeys) {
                if (tryAuth(mfc, sector, k)) { authed = true; break }
            }
            // Доп. авторизация: если первый список не подошёл — пробуем второй
            // (для newCard это ASOP keys = partial-write state; для reflash это factory keys).
            val altKeys: List<ByteArray> = if (authKeys === FACTORY_KEYS) listOf(keyA, keyB) else FACTORY_KEYS
            var usedAsopKey = false
            if (!authed) {
                Log.d(TAG, "newCard: sector $sector primary auth FAIL — try alternate keys (alt=${altKeys.size})")
                for (k in altKeys) {
                    if (tryAuth(mfc, sector, k)) {
                        authed = true
                        usedAsopKey = (k === keyA || k === keyB)
                        Log.d(TAG, "newCard: sector $sector alt auth OK (asop=$usedAsopKey)")
                        break
                    }
                }
            }
            if (!authed) {
                Log.w(TAG, "newCard: sector $sector auth FAIL (no keys worked)")
                steps += "auth sector $sector FAIL"
                return Triple(false, steps, "auth сектор $sector не прошёл ни одним ключом")
            }
            Log.d(TAG, "newCard: sector $sector auth OK (usedAsopKey=$usedAsopKey)")
            steps += "auth sector $sector OK"

            if (!isExisting && !usedAsopKey) {
                // Записать trailer: KeyA(6) | AccessBits(4) | KeyB(6).
                // NOTE: на Classic-клонах побайтовый readBack часто возвращает мусор —
                // настоящим доказательством успешной записи trailer является auth с НОВЫМ KeyA:
                // если он проходит — ключи записались. Используем именно этот критерий.
                val trailerBlockIdx = mfc.sectorToBlock(sector) + mfc.getBlockCountInSector(sector) - 1
                val trailer = ByteArray(BLOCK_SIZE)
                System.arraycopy(keyA, 0, trailer, 0, 6)
                System.arraycopy(ACCESS_BITS, 0, trailer, 6, 4)
                System.arraycopy(keyB, 0, trailer, 10, 6)
                try {
                    mfc.writeBlock(trailerBlockIdx, trailer)
                    Log.d(TAG, "newCard: sector $sector trailer written (KeyA=${keyA.toHexShort()})")
                    steps += "trailer sector $sector written (KeyA=${keyA.toHexShort()}, AB=${ACCESS_BITS.toHex()}, KeyB=${keyB.toHexShort()})"
                } catch (e: IOException) {
                    Log.w(TAG, "newCard: sector $sector trailer IOException: ${e.message}")
                    return Triple(false, steps, "writeBlock trailer sector $sector IOException: ${e.message}")
                }
                // После writeFactory ключи в trailer должны смениться. Проверяем через reauth.
                // Краткая пауза — clone-картам нужно ~50-100 мс на commit'нуть writeBlock.
                Thread.sleep(80)
                if (!tryAuth(mfc, sector, keyA)) {
                    Log.w(TAG, "newCard: sector $sector reauth FAIL — clone commit не прошёл")
                    return Triple(false, steps, "reauth sector $sector новым KeyA не прошёл (клон-карта не подтвердила запись trailer)")
                }
                Log.d(TAG, "newCard: sector $sector reauth OK newKey (trailer verification)")
                steps += "trailer verified by reauth with newKeyA"
            } else {
                Log.d(TAG, "newCard: sector $sector skipping trailer write (usedAsopKey=$usedAsopKey, isExisting=$isExisting)")
            }

            // Записать data-блоки (кроме trailer — последний)
            val blockCount = mfc.getBlockCountInSector(sector)
            val base = mfc.sectorToBlock(sector)
            val writtenInSector = dataBlockIdx
            for (dataIdx in 0 until blockCount - 1) {
                if (dataBlockIdx * BLOCK_SIZE >= payload.size) break
                val src = payload.copyOfRange(dataBlockIdx * BLOCK_SIZE, (dataBlockIdx + 1) * BLOCK_SIZE)
                try {
                    mfc.writeBlock(base + dataIdx, src)
                } catch (e: IOException) {
                    Log.w(TAG, "newCard: sector $sector block $dataIdx IOException: ${e.message}")
                    return Triple(false, steps, "writeBlock sector $sector block $dataIdx IOException: ${e.message}")
                }
                val readBackData = try { mfc.readBlock(base + dataIdx) } catch (e: IOException) { null }
                if (readBackData == null || !readBackData.contentEquals(src)) {
                    Log.w(TAG, "newCard: sector $sector block $dataIdx read-back mismatch")
                    return Triple(false, steps, "read-back sector $sector block $dataIdx mismatch")
                }
                dataBlockIdx++
            }
            val delta = dataBlockIdx - writtenInSector
            Log.d(TAG, "newCard: sector $sector: $delta data-блоков записано (всего $dataBlockIdx / dataBlocks=${payload.size / BLOCK_SIZE})")
            steps += "sector $sector: $dataBlockIdx data-блоков записано"
            if (dataBlockIdx * BLOCK_SIZE >= payload.size) break
        }

        // Финальная верификация: прочитать обратно и распарсить SAC1
        // (для уже записанной карты; используем перебор candidate-ключей: новый KeyA после WriteTrailer)
        val verifyKeys = if (isExisting) authKeys else listOf(keyA, keyB)
        val reRead = readFromWritten(mfc, verifyKeys)
        if (reRead == null) {
            return Triple(false, steps, "финальная верификация SAC1-payload не удалась")
        }
        steps += "verification: SAC1 OK (proto=${reRead.protoBytes.size}, sig=${reRead.signatureRaw.size})"

        return Triple(true, steps, null)
    }

    private fun readFromWritten(mfc: MifareClassic, keys: List<ByteArray>): Sac1Payload? {
        // mfc.auth сохраняется до закрытия; но чтобы избежать проблем, делаем явные auth.
        val collected = mutableListOf<Byte>()
        outer@ for (sector in FIRST_IDENTITY_SECTOR..LAST_IDENTITY_SECTOR) {
            var authed = false
            for (k in keys) {
                if (tryAuth(mfc, sector, k)) { authed = true; break }
            }
            if (!authed) return null
            val blockCount = mfc.getBlockCountInSector(sector)
            val base = mfc.sectorToBlock(sector)
            for (dataIdx in 0 until blockCount - 1) {
                try {
                    val b = mfc.readBlock(base + dataIdx)
                    collected.addAll(b.toList())
                } catch (e: IOException) {
                    return null
                }
                val buf = collected.toByteArray()
                if (buf.size >= 9 && buf.copyOfRange(0, 4).contentEquals(SAC1_MAGIC)) {
                    val lp = (buf[5].toInt() and 0xFF) or ((buf[6].toInt() and 0xFF) shl 8)
                    val ls = (buf[7].toInt() and 0xFF) or ((buf[8].toInt() and 0xFF) shl 8)
                    if (lp > 0 && ls > 0 && buf.size >= 9 + lp + ls) {
                        break@outer
                    }
                }
            }
        }
        return parseSac1Payload(collected.toByteArray())
    }

    private fun tryAuth(mfc: MifareClassic, sector: Int, key: ByteArray): Boolean {
        return try {
            mfc.authenticateSectorWithKeyA(sector, key)
        } catch (e: Exception) {
            false
        }
    }

    /** Выравнивание payload до длины, кратной BLOCK_SIZE (добивается нулями). */
    fun padPayload(payload: ByteArray): ByteArray {
        if (payload.size % BLOCK_SIZE == 0) return payload
        val padded = ByteArray(((payload.size / BLOCK_SIZE) + 1) * BLOCK_SIZE)
        System.arraycopy(payload, 0, padded, 0, payload.size)
        return padded
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { String.format("%02X", it) }
    private fun ByteArray.toHexShort(): String = take(2).joinToString("") { String.format("%02X", it) } + "…"
}
