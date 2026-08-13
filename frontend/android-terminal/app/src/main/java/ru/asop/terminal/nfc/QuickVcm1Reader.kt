package ru.asop.terminal.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * Лёгкий helper для чтения VCM1-payload (sector 1, 3 data-блока) карты водителя
 * БЕЗ полного dump всех секторов. Используется в SessionFlowScreen/OpenShiftScreen
 * и других session-flow screens где нужно быстро прочитать идентичность
 * водителя и подтвердить открытие/закрытие смены/рейса.
 *
 * Возвращает (uid_hex, vcm1Bytes) или null если карта не MifareClassic или auth
 * провалился на sector 1.
 *
 * Все ASOP-ключи добавляются как кандидаты для KeyA/KeyB после factory-keys
 * и null/all-FF — те же паттерны что и в большом `MifareClassicReader.read()`,
 * но без рекурсивного auth всех 16 секторов.
 */
object QuickVcm1Reader {

    private const val TAG = "QuickVcm1Reader"

    data class ReadOutcome(
        val uidHex: String,
        val vcm1Bytes: ByteArray,
        val status: Status,
        val details: String
    ) {
        enum class Status {
            /** Успех: VCM1 identity прочитан. */
            OK,
            /** Карта не MifareClassic (не поддерживается). */
            NOT_MIFARE_CLASSIC,
            /** Удалось auth но блок 0 не содержит VCM1 magic («VCM1»). */
            NOT_VCM1,
            /** Ни один из candidate keys не авторизовал sector 1. */
            AUTH_FAILED,
            /** Исключение при чтении блоков (timeout, IOException). */
            READ_FAILED
        }
    }

    /**
     * Читает только sector 1 (3 data-блока: 0=magic+bitmask, 1=cardId UUID,
     * 2=entity UUID). Подробный отчёт — в [ReadOutcome] чтобы ViewModel мог
     * показать пользователю понятную ошибку вместо пустого NFC_ERROR.
     *
     * @param tag Android Tag от onTagDiscovered()
     * @param asopKeyMaterial список 24-байтных ASOP-ключей из terminal_keys.
     *        Берём KeyA=key[0..5], KeyB=key[6..11]. Остальные байты игнор.
     */
    fun read(tag: Tag, asopKeyMaterial: List<ByteArray>): ReadOutcome? {
        Log.i(TAG, "onTag: techList=${tag.techList.joinToString(",")}")
        val mfc = MifareClassic.get(tag) ?: return ReadOutcome(
            uidHex = "",
            vcm1Bytes = ByteArray(0),
            status = ReadOutcome.Status.NOT_MIFARE_CLASSIC,
            details = "Карта не MifareClassic (теги: ${tag.techList.joinToString(",")}). Используйте MIFARE Classic 1K/4K."
        )

        val uidHex = try {
            mfc.tag.id.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return ReadOutcome(
                uidHex = "",
                vcm1Bytes = ByteArray(0),
                status = ReadOutcome.Status.NOT_MIFARE_CLASSIC,
                details = "Не удалось прочитать UID: ${e.message}"
            )
        }
        Log.i(TAG, "uid=$uidHex sectorCount=${mfc.sectorCount}")

        try {
            mfc.connect()
            mfc.timeout = 2500

            val candidates = buildCandidates(asopKeyMaterial)
            Log.i(TAG, "uid=$uidHex asop-keys=${asopKeyMaterial.size} candidates=${candidates.size}")

            for ((idx, key) in candidates.withIndex()) {
                val keyHex = key.joinToString("") { String.format("%02X", it) }
                val authA = try { mfc.authenticateSectorWithKeyA(1, key) } catch (e: Exception) {
                    Log.w(TAG, "authA key=$keyHex failed: ${e.message}"); false
                }
                val authB = if (!authA) try { mfc.authenticateSectorWithKeyB(1, key) } catch (e: Exception) {
                    Log.w(TAG, "authB key=$keyHex failed: ${e.message}"); false
                } else false
                Log.d(TAG, "candidate #$idx key=$keyHex authA=$authA authB=$authB")
                if (!authA && !authB) continue

                // auth прошёл — читаем 3 data-блока sector 1 (block indices 4,5,6)
                val block0 = try { mfc.readBlock(4) } catch (e: Exception) {
                    Log.w(TAG, "readBlock 4 failed: ${e.message}")
                    return ReadOutcome(uidHex, ByteArray(0), ReadOutcome.Status.READ_FAILED,
                        "Ошибка чтения блока 4: ${e.message}")
                }
                val block1 = try { mfc.readBlock(5) } catch (e: Exception) {
                    return ReadOutcome(uidHex, ByteArray(0), ReadOutcome.Status.READ_FAILED,
                        "Ошибка чтения блока 5: ${e.message}")
                }
                val block2 = try { mfc.readBlock(6) } catch (e: Exception) {
                    return ReadOutcome(uidHex, ByteArray(0), ReadOutcome.Status.READ_FAILED,
                        "Ошибка чтения блока 6: ${e.message}")
                }

                val vcm1Bytes = ByteArray(48).also {
                    System.arraycopy(block0, 0, it, 0, 16)
                    System.arraycopy(block1, 0, it, 16, 16)
                    System.arraycopy(block2, 0, it, 32, 16)
                }
                Log.d(TAG, "block0[0..4] hex=" + block0.take(6).joinToString("") { String.format("%02X", it) })

                if (block0.size >= 4 &&
                    block0[0] == 'V'.code.toByte() &&
                    block0[1] == 'C'.code.toByte() &&
                    block0[2] == 'M'.code.toByte() &&
                    block0[3] == '1'.code.toByte()
                ) {
                    Log.i(TAG, "VCM1 OK key=$keyHex uid=$uidHex")
                    return ReadOutcome(uidHex, vcm1Bytes, ReadOutcome.Status.OK,
                        "VCM1 OK auth=$keyHex")
                } else {
                    val block0Ascii = block0.take(4).joinToString("") {
                        if (it in 0x20.toByte()..0x7E.toByte()) it.toInt().toChar().toString() else "?"
                    }
                    return ReadOutcome(uidHex, vcm1Bytes, ReadOutcome.Status.NOT_VCM1,
                        "Карта не VCM1-формата. Sector 1 block 0 magic='$block0Ascii' " +
                            "(ожидалось 'VCM1'). Карта активирована через «Активация карт» в меню?")
                }
            }

            // Ни один кандидат не прошёл auth — но прочитаем manufacturer block 0 чтобы
            // помочь пользователю понять — это карта ASOP (VCM1-magic не записан) или чужой ключ
            val manufacturerBlock = try { mfc.readBlock(0) } catch (_: Exception) { ByteArray(0) }
            val uidEcho = if (manufacturerBlock.size >= 4) {
                "manufacturer-block-0='" + manufacturerBlock.take(4).joinToString("") {
                    String.format("%02X", it)
                } + "'"
            } else ""

            return ReadOutcome(uidHex, ByteArray(0), ReadOutcome.Status.AUTH_FAILED,
                "Не удалось авторизовать sector 1 ни одним ключом " +
                    "(${candidates.size} кандидатов). " +
                    "Проверьте: (а) карта активирована, (б) ASOP-ключи ('Загрузить справочники' → 'Полная выкачка'), " +
                    "$uidEcho")
        } catch (e: Exception) {
            Log.e(TAG, "read exception", e)
            return ReadOutcome(uidHex, ByteArray(0), ReadOutcome.Status.READ_FAILED,
                "Исключение при чтении: ${e.message}")
        } finally {
            try { mfc.close() } catch (_: Exception) { }
        }
    }

    /* legacy simple API kept for compat with earlier callers */
    fun readVcm1Identity(tag: Tag, asopKeyMaterial: List<ByteArray>): Pair<String, ByteArray>? {
        val outcome = read(tag, asopKeyMaterial) ?: return null
        return if (outcome.status == ReadOutcome.Status.OK) outcome.uidHex to outcome.vcm1Bytes else null
    }

    private fun buildCandidates(asopKeyMaterial: List<ByteArray>): List<ByteArray> {
        val unique = linkedSetOf<String>()
        val out = mutableListOf<ByteArray>()

        fun add(k: ByteArray) {
            if (k.size != 6) return
            val hex = k.joinToString("") { String.format("%02X", it) }
            if (unique.add(hex)) out.add(k)
        }

        for (key in asopKeyMaterial) {
            if (key.size < 12) continue
            add(key.copyOfRange(0, 6))
            add(key.copyOfRange(6, 12))
        }

        // CRITICAL: factory default FF FF FF FF FF FF + special zero key — активированная
        // карта перешифровывает KeyA/KeyB из ASOP_KEYS, но если ключи ещё не синкнулись
        // на терминал — карта лежит с factory-default. Поэтому пробуем их first.
        add(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        add(byteArrayOf(0, 0, 0, 0, 0, 0))
        add(byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()))
        add(byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte()))
        return out
    }
}
