package ru.asop.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.util.Log

/**
 * Единый читатель ASOP-карт (VCM1) для всех flow (открытие смены / активация / top-up).
 *
 * Быстрое чтение ТОЛЬКО сектора 1 (auth + 4 блока VCM1) — без сканирования всех
 * 16 секторов × 3 прохода.
 *
 * ReadResult/Session: запись tripsLeft возможна ТОЛЬКО в той же mfc-сессии, что и чтение
 * (Feitian F20: reconnect по тому же Tag после close() падает IOException(null) —
 * writeTripsLeft с отдельным connect() на этой модели не работает).
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
            NOT_VCM1,
        }
    }

    data class ReadResult(val outcome: Outcome, val session: Session?)

    /**
     * Живая mfc-сессия после успешного чтения sector 1 (auth уже пройден тем же ключом).
     * Позволяет дописать tripsLeft в той же сессии — [updateTrips].
     */
    class Session internal constructor(
        private val mfc: MifareClassic,
        private val base: Int,
        private val sector: Int,
        private val keyA: ByteArray
    ) {
        fun updateTrips(newTripsLeft: Int): Boolean {
            if (newTripsLeft !in 0..0xFFFF) return false
            return try {
                if (!mfc.isConnected) {
                    Log.d("Vcm1CardAuth", "updateTrips: reconnect (was disconnected)")
                    mfc.connect()
                    mfc.timeout = 3000
                }
                try {
                    mfc.authenticateSectorWithKeyA(sector, keyA)
                } catch (e: Exception) {
                    Log.w("Vcm1CardAuth", "updateTrips: re-auth failed: ${e.message}")
                    return false
                }
                val block0 = mfc.readBlock(base)
                if (block0.size != CardIdentityVcm1.BLOCK_SIZE) return false
                block0[CardIdentityVcm1.TRIPS_OFFSET] = (newTripsLeft and 0xFF).toByte()
                block0[CardIdentityVcm1.TRIPS_OFFSET + 1] = ((newTripsLeft shr 8) and 0xFF).toByte()
                mfc.writeBlock(base, block0)
                val readBack = mfc.readBlock(base)
                readBack.contentEquals(block0)
            } catch (e: Exception) {
                Log.w("Vcm1CardAuth", "updateTrips: io error: ${e.message}")
                false
            }
        }

        fun close() {
            runCatching { mfc.close() }
        }
    }

    /** Чтение без сохранения сессии. */
    fun read(tag: Tag, asopKeyMaterial: List<ByteArray>): Outcome {
        val result = readWithSession(tag, asopKeyMaterial)
        result.session?.close()
        return result.outcome
    }

    /**
     * Чтение с сохранением открытой сессии — для flow «read + debit/credit trips» в одном
     * mfc-подключении. Caller ОБЯЗАН вызвать [Session.close].
     */
    fun readWithSession(tag: Tag, asopKeyMaterial: List<ByteArray>): ReadResult {
        val uidHex = tag.id.joinToString("") { "%02X".format(0xFF and it.toInt()) }
        if (MifareClassic.get(tag) == null) {
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
                        return ReadResult(Outcome.Ok(uidHex = uidHex, rawVcm1Bytes = raw, identity = identity),
                            Session(mfc, base, sector, keyA))
                    }
                } catch (_: Exception) { /* не VCM1 */ }
            }
            runCatching { mfc.close() }
            return ReadResult(Outcome.Failed(uidHex = uidHex, status = Outcome.Status.AUTH_FAILED,
                details = "Нет подходящего ASOP-ключа. Карта не активирована?"), null)
        } catch (e: Exception) {
            runCatching { mfc.close() }
            return ReadResult(Outcome.Failed(uidHex = uidHex, status = Outcome.Status.READ_FAILED,
                details = "Ошибка чтения: ${e.message}"), null)
        }
    }

    /** Разбивает 24-байтные ASOP-ключи на 6-байтные KeyA/KeyB пары. */
    fun splitKeyMaterial(fullKeys: List<ByteArray>): List<ByteArray> = fullKeys.flatMap { full ->
        when {
            full.size == 6 -> listOf(full)
            full.size >= 12 -> listOf(full.copyOfRange(0, 6), full.copyOfRange(6, 12))
            else -> emptyList()
        }
    }
}