package ru.asop.terminal.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import ru.asop.terminal.activation.CardIdentityVcm1

/**
 * Промпт 014: единый читатель ASOP-карт для всех flow (open shift / activate card / read probe).
 *
 * Реализация:
 *   1. Тех-тег через MifareClassicReader.isMifareClassic()
 *   2. ASOP-keys из terminal_keys (фактические + factory-FF / 00 / A0 / D3F7)
 *   3. Быстрое чтение ТОЛЬКО сектора 1 (auth + 4 блока VCM1) — без сканирования всех
 *      16 секторов × 3 прохода (убрано в Промпт 014 по запросу оператора: полный скан
 *      давал ~6с чтения и многократные писки, хотя для открытия смены нужен только VCM1).
 *   4. Полный скан (MifareClassicReader.read()) используется только в «Прочитать карту».
 *
 * Возвращает Vcm1AuthOutcome — общий тип для всех UM-флоу.
 *
 * ReadResult/Session: запись tripsLeft возможна ТОЛЬКО в той же mfc-сессии, что и чтение
 * (Feitian F20: reconnect по тому же Tag после close() падает IOException(null) —
 * фича writeTripsLeft с отдельным connect() на этой модели не работает).
 */
object Vcm1CardAuth {

    sealed interface Outcome {
        data class Ok(
            val uidHex: String,
            val rawVcm1Bytes: ByteArray,
            val identity: CardIdentityVcm1
        ) : Outcome

        data class Failed(
            val uidHex: String,
            val status: Status,
            val details: String
        ) : Outcome

        enum class Status {
            NOT_MIFARE_CLASSIC,
            AUTH_FAILED,
            READ_FAILED,
            NOT_VCM1,           // прочитана mifare classic, но sector 1 не содержит VCM1-magic
        }
    }

    /** Исход чтения + открытая сессия (не null только при Outcome.Ok). */
    data class ReadResult(val outcome: Outcome, val session: Session?)

    /**
     * Живая mfc-сессия после успешного чтения sector 1 (auth уже пройден тем же ключом).
     * Позволяет дописать tripsLeft БЕЗ reconnect — [updateTrips].
     */
    class Session internal constructor(
        private val mfc: MifareClassic,
        private val base: Int
    ) {
        /**
         * Патчит только байты [10..11] block 0 (tripsLeft UInt16 LE), cardId/bitmask/magic
         * не трогает. Read-back верификация. true = записано и подтверждено.
         */
        fun updateTrips(newTripsLeft: Int): Boolean {
            if (newTripsLeft !in 0..0xFFFF) return false
            return try {
                val block0 = mfc.readBlock(base)
                if (block0.size != CardIdentityVcm1.BLOCK_SIZE) return false
                block0[CardIdentityVcm1.TRIPS_OFFSET] = (newTripsLeft and 0xFF).toByte()
                block0[CardIdentityVcm1.TRIPS_OFFSET + 1] = ((newTripsLeft shr 8) and 0xFF).toByte()
                mfc.writeBlock(base, block0)
                val readBack = mfc.readBlock(base)
                readBack.contentEquals(block0)
            } catch (_: Exception) {
                false
            }
        }

        fun close() {
            runCatching { mfc.close() }
        }
    }

    /**
     * Промпт 014: быстрое чтение ТОЛЬКО sector 1 (auth + 4 блока VCM1).
     * Для открытия смены / auth-карты / любых flow кроме «Прочитать карту».
     * @return [Outcome.Ok] если VCM1-magic найден; [Outcome.Failed] иначе.
     */
    fun read(tag: Tag, asopKeyMaterial: List<ByteArray>): Outcome {
        val result = readWithSession(tag, asopKeyMaterial)
        result.session?.close()
        return result.outcome
    }

    /**
     * Чтение с сохранением открытой сессии — для flow «read + debit trips» в одном
     * mfc-подключении (Feitian: второй connect() по тому же Tag не работает).
     * Caller ОБЯЗАН вызвать [Session.close].
     */
    fun readWithSession(tag: Tag, asopKeyMaterial: List<ByteArray>): ReadResult {
        val uidHex = tag.id.joinToString("") { "%02X".format(0xFF and it.toInt()) }
        if (!MifareClassicReader.isMifareClassic(tag)) {
            return ReadResult(Outcome.Failed(
                uidHex = uidHex,
                status = Outcome.Status.NOT_MIFARE_CLASSIC,
                details = "Карта не MifareClassic (теги: ${tag.techList.joinToString(",")})."
            ), null)
        }

        val mfc = try { MifareClassic.get(tag) } catch (_: Exception) { null }
        if (mfc == null) {
            return ReadResult(Outcome.Failed(uidHex = uidHex, status = Outcome.Status.READ_FAILED,
                details = "Не удалось получить MifareClassic из Tag"), null)
        }
        try { mfc.connect() } catch (e: Exception) {
            runCatching { mfc.close() }
            return ReadResult(Outcome.Failed(uidHex = uidHex, status = Outcome.Status.READ_FAILED,
                details = "Не удалось подключиться: ${e.message}"), null)
        }
        try {
            val sector = 1
            val base = mfc.sectorToBlock(sector)
            // Разбиваем 24-байтные ASOP-ключи на 6-байтные KeyA/KeyB и пробуем
            for (fullKey in asopKeyMaterial) {
                if (fullKey.size < 6) continue
                val keyA = if (fullKey.size >= 12) fullKey.copyOfRange(0, 6) else fullKey
                try { mfc.authenticateSectorWithKeyA(sector, keyA) } catch (_: Exception) { continue }
                val blocks = (0 until 3).map { i ->
                    try { mfc.readBlock(base + i) } catch (_: Exception) { null }
                }
                if (blocks.any { it == null }) continue
                val raw = ByteArray(48).also { v ->
                    blocks.forEachIndexed { idx, b -> if (b != null) b.copyInto(v, idx * 16) }
                }
                try {
                    val identity = CardIdentityVcm1.decodeFromBytes(raw)
                    if (identity != null) {
                        // mfc остаётся открытым — сессию закрывает caller через Session.close()
                        return ReadResult(Outcome.Ok(uidHex = uidHex, rawVcm1Bytes = raw, identity = identity),
                            Session(mfc, base))
                    }
                } catch (_: Exception) { /* не VCM1 */ }
            }
            // Ни один ключ не подошёл или не VCM1
            runCatching { mfc.close() }
            return ReadResult(Outcome.Failed(uidHex = uidHex, status = Outcome.Status.AUTH_FAILED,
                details = "Нет подходящего ASOP-ключа. Карта не активирована?"), null)
        } catch (e: Exception) {
            runCatching { mfc.close() }
            return ReadResult(Outcome.Failed(uidHex = uidHex, status = Outcome.Status.READ_FAILED,
                details = "Ошибка чтения: ${e.message}"), null)
        }
    }

}
