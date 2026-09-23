package ru.asop.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.util.Log
import java.io.IOException

/**
 * Низкоуровневые операции MIFARE Classic (VCM1, sector 1).
 *
 * Используется Android tech API `MifareClassic` (CRYPTO1-рукопожатие инкапсулировано).
 *
 * Формат payload на карте (VCM1, промпт 008/014): только sector 1
 * (3 data-блока + trailer): block 0 magic+bitmask+tripsLeft, block 1 cardId,
 * block 2 entityUuid. Sectors 2..15 NO-OP.
 *
 * Трейлер: KeyA(6) | AccessBits(4) | KeyB(6), access bits `FF 07 80 69`.
 */
class MifareClassicVcm1 {

    data class WriteResult(
        val ok: Boolean,
        val steps: List<String>,
        val error: String?
    )

    enum class SectorState { NEW, EXISTING, UNRECOGNIZED }

    companion object {
        private const val TAG = "MifareClassicVcm1"
        private const val SECTOR = 1
        private const val BLOCK_SIZE = 16

        val ACCESS_BITS: ByteArray = byteArrayOf(
            0xFF.toByte(), 0x07, 0x80.toByte(), 0x69
        )

        val NULL_KEY_A: ByteArray = byteArrayOf(0, 0, 0, 0, 0, 0)

        val FACTORY_KEYS: List<ByteArray> = listOf(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
            byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte())
        )
    }

    /** Определяет состояние sector 1: NEW (factory auth) / EXISTING (ASOP/NULL key) / UNRECOGNIZED. */
    fun detectState(tag: Tag, candidateKeys: List<ByteArray>): SectorState {
        val mfc = MifareClassic.get(tag) ?: return SectorState.UNRECOGNIZED
        return try {
            mfc.connect()
            mfc.timeout = 3000
            for (factory in FACTORY_KEYS) {
                if (tryAuthKeyA(mfc, factory)) return SectorState.NEW
            }
            if (tryAuthKeyA(mfc, NULL_KEY_A)) return SectorState.EXISTING
            val six = Vcm1CardAuth.splitKeyMaterial(candidateKeys)
            for (key in six) {
                if (tryAuthKeyA(mfc, key) || tryAuthKeyB(mfc, key)) return SectorState.EXISTING
            }
            SectorState.UNRECOGNIZED
        } catch (e: Exception) {
            Log.w(TAG, "detectState: ${e.message}")
            SectorState.UNRECOGNIZED
        } finally {
            runCatching { mfc.close() }
        }
    }

    /**
     * Прошивка VCM1-identity на MIFARE Classic: только sector 1 (3 data-блока + trailer).
     * @param keyA/KeyB новые 6-байтные ключи (пишутся в trailer, если карта не защищена рабочим ключом).
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
        require(keyA.size == 6 && keyB.size == 6) { "keys должны быть 6 байт" }
        val payload = vcm1.encodeAsBytes()
        require(payload.size == CardIdentityVcm1.TOTAL_BYTES) { "VCM1 payload size mismatch" }
        return multiPassWrite(tag) { mfc ->
            val authCandidates: List<ByteArray> = if (isExisting) {
                listOf(workingKeyA, workingKeyB, NULL_KEY_A) + FACTORY_KEYS
            } else {
                FACTORY_KEYS + listOf(NULL_KEY_A) + listOf(workingKeyA, workingKeyB)
            }
            writeVcm1Internal(mfc, payload, authCandidates, keyA, keyB, isExisting, workingKeyA, workingKeyB)
        }
    }

    /** Одноразовая запись tripsLeft (новая mfc-сессия). Для Feitian используйте Session.updateTrips. */
    fun writeTripsLeft(tag: Tag, candidateKeys: List<ByteArray>, newTripsLeft: Int): Pair<Int, Int>? {
        repeat(3) { attempt ->
            val r = writeTripsLeftOnce(tag, candidateKeys, newTripsLeft)
            if (r != null) return r
            Log.w(TAG, "writeTripsLeft attempt #$attempt failed, retrying...")
            Thread.sleep(120)
        }
        return null
    }

    // ---------- internal ----------

    private fun multiPassWrite(
        tag: Tag,
        op: (MifareClassic) -> Triple<Boolean, List<String>, String?>
    ): WriteResult {
        val combinedSteps = mutableListOf<String>()
        repeat(3) { attempt ->
            val mfc = MifareClassic.get(tag)
                ?: return WriteResult(false, combinedSteps, "MifareClassic недоступен (карта не Classic?)")
            try {
                runCatching { if (mfc.isConnected) mfc.close() }
                Thread.sleep(120)
                mfc.connect()
                mfc.timeout = 5000
                val (ok, attSteps, err) = op(mfc)
                combinedSteps.addAll(attSteps)
                if (ok) {
                    runCatching { if (mfc.isConnected) mfc.close() }
                    return WriteResult(true, combinedSteps, null)
                }
                if (err?.contains("auth сектор") == true || err?.contains("Tag was lost") == true) {
                    combinedSteps += "pass $attempt auth FAIL — sleeping 800ms"
                    Thread.sleep(800)
                } else {
                    runCatching { if (mfc.isConnected) mfc.close() }
                    return WriteResult(false, combinedSteps, err)
                }
            } catch (e: Exception) {
                combinedSteps += "pass $attempt exception: ${e.javaClass.simpleName}: ${e.message}"
                runCatching { if (mfc.isConnected) mfc.close() }
                Thread.sleep(800)
            }
        }
        return WriteResult(false, combinedSteps,
            "auth сектор 1 не прошёл за 3 попытки (клон-карта теряет сессию между detect и write)")
    }

    private fun writeVcm1Internal(
        mfc: MifareClassic,
        payload: ByteArray,
        authKeys: List<ByteArray>,
        keyA: ByteArray,
        keyB: ByteArray,
        isExisting: Boolean,
        workingKeyA: ByteArray,
        workingKeyB: ByteArray
    ): Triple<Boolean, List<String>, String?> {
        val steps = mutableListOf<String>()
        val base = mfc.sectorToBlock(SECTOR)
        val blockCount = mfc.getBlockCountInSector(SECTOR)
        val trailerIdx = base + blockCount - 1

        var authed = false
        var usedExistingKey = false
        for (k in authKeys) {
            if (tryAuthKeyA(mfc, k)) {
                authed = true
                usedExistingKey = isExisting && (k.contentEquals(workingKeyA) || k.contentEquals(workingKeyB))
                break
            }
        }
        if (!authed) {
            steps += "auth sector $SECTOR FAIL"
            return Triple(false, steps, "auth сектор $SECTOR не прошёл ни одним ключом")
        }
        steps += "auth sector $SECTOR OK"

        for (dataIdx in 0 until CardIdentityVcm1.USED_BLOCK_COUNT) {
            val src = payload.copyOfRange(dataIdx * BLOCK_SIZE, (dataIdx + 1) * BLOCK_SIZE)
            val blockIdx = base + dataIdx
            try {
                mfc.writeBlock(blockIdx, src)
            } catch (e: IOException) {
                return Triple(false, steps, "writeBlock $blockIdx IOException: ${e.message}")
            }
            val readBack = try { mfc.readBlock(blockIdx) } catch (e: IOException) { null }
            if (readBack == null || !readBack.contentEquals(src)) {
                return Triple(false, steps, "read-back block $blockIdx mismatch (clone likely)")
            }
        }
        steps += "VCM1 3 data-блока записано и verified"

        if (!usedExistingKey) {
            val trailer = ByteArray(BLOCK_SIZE)
            System.arraycopy(keyA, 0, trailer, 0, 6)
            System.arraycopy(ACCESS_BITS, 0, trailer, 6, 4)
            System.arraycopy(keyB, 0, trailer, 10, 6)
            try {
                mfc.writeBlock(trailerIdx, trailer)
            } catch (e: IOException) {
                return Triple(false, steps, "writeBlock trailer IOException: ${e.message}")
            }
            Thread.sleep(80)
            if (!tryAuthKeyA(mfc, keyA)) {
                return Triple(false, steps, "reauth newKey FAIL (клон-карта не подтвердила trailer)")
            }
            steps += "trailer written + reauth OK newKey"
        } else {
            steps += "trailer skipped (карта уже защищена нашим ключом)"
        }

        return Triple(true, steps, null)
    }

    private fun writeTripsLeftOnce(
        tag: Tag,
        candidateKeys: List<ByteArray>,
        newTripsLeft: Int
    ): Pair<Int, Int>? {
        if (newTripsLeft !in 0..0xFFFF) return null
        val mfc = MifareClassic.get(tag) ?: return null
        return try {
            mfc.connect()
            mfc.timeout = 3000
            val base = mfc.sectorToBlock(SECTOR)

            val six = Vcm1CardAuth.splitKeyMaterial(candidateKeys)
            var authedKey: ByteArray? = null
            for (key in six) {
                try { mfc.authenticateSectorWithKeyA(SECTOR, key); authedKey = key; break } catch (_: Exception) {}
            }
            if (authedKey == null) {
                for (key in six) {
                    try { mfc.authenticateSectorWithKeyB(SECTOR, key); authedKey = key; break } catch (_: Exception) {}
                }
            }
            if (authedKey == null) return null

            val block0 = try { mfc.readBlock(base) } catch (e: IOException) { return null }
            if (block0.size != BLOCK_SIZE) return null
            val oldTrips = ((block0[CardIdentityVcm1.TRIPS_OFFSET].toInt() and 0xFF)) or
                ((block0[CardIdentityVcm1.TRIPS_OFFSET + 1].toInt() and 0xFF) shl 8)

            block0[CardIdentityVcm1.TRIPS_OFFSET] = (newTripsLeft and 0xFF).toByte()
            block0[CardIdentityVcm1.TRIPS_OFFSET + 1] = ((newTripsLeft shr 8) and 0xFF).toByte()

            try { mfc.writeBlock(base, block0) } catch (e: IOException) { return null }
            val readBack = try { mfc.readBlock(base) } catch (e: IOException) { null }
            if (readBack == null || !readBack.contentEquals(block0)) return null
            Log.i(TAG, "writeTripsLeft: $oldTrips -> $newTripsLeft OK")
            oldTrips to newTripsLeft
        } catch (e: Exception) {
            Log.w(TAG, "writeTripsLeft error: ${e.message}")
            null
        } finally {
            runCatching { mfc.close() }
        }
    }

    private fun tryAuthKeyA(mfc: MifareClassic, key: ByteArray): Boolean = try {
        mfc.authenticateSectorWithKeyA(SECTOR, key)
        true
    } catch (e: Exception) {
        false
    }

    private fun tryAuthKeyB(mfc: MifareClassic, key: ByteArray): Boolean = try {
        mfc.authenticateSectorWithKeyB(SECTOR, key)
        true
    } catch (e: Exception) {
        false
    }
}