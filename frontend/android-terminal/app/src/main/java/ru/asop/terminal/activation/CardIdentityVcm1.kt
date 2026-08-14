package ru.asop.terminal.activation

import com.github.f4b6a3.uuid.UuidCreator
import java.util.UUID

/**
 * VCM1 (Versioned Compact Mifare 1) — компактный unsigned-формат для MIFARE Classic
 * (промпт 008). На карте лежит ТОЛЬКО в sector 1 (3 data-блока):
 *
 * Layout (block 16 bytes каждый):
 *  block 0: "VCM1" magic [0..3] + bitmask UInt16 LE [4..5] + tripsLeft UInt16 LE [10..11] + reserved/zeros
 *  block 1: cardId UUID v7 binary (16 raw bytes, RFC 4122 MSB-first)
 *  block 2: entityUuid UUID v7 binary (16 raw bytes) — single-slot highest-bit-wins role;
 *            zeroed для PASSENGER_ANONYMOUS
 *
 * Промпт 014: байты [10..11] (ранее reserved) хранят количество поездок UInt16 LE.
 * Старые VCM1-карты с нулями в [10..11] читаются как tripsLeft = 0 — обратно совместимо.
 *
 * Sector 2 и sectors 3-4: zeroed / no-op (резерв на будущее).
 */
data class CardIdentityVcm1(
    val cardId: UUID,
    /** UInt16 — битовая маска ролей. bit i = AsopCardType.ordinal(i). */
    val bitmask: Int,
    /**
     * Single-slot UUID для highest-bit role. Для PASSENGER_ANONYMOUS — `null`
     * (тогда entityUuid = 16 zero bytes на карте).
     */
    val entity: EntityRef?,
    /** Промпт 014: количество поездок на карте (UInt16 LE в block 0 [10..11]). */
    val tripsLeft: Int = 0,
    /** Magic identifier ('V' 'C' 'M' '1' = ASCII 0x56 0x43 0x4D 0x31). */
    val version: Int = 1
) {
    fun encodeAsBytes(): ByteArray {
        require(bitmask in 0..0x3FFF) {
            "bitmask out of range: 0x${bitmask.toString(16)} (must be 0..0x3FFF)"
        }
        require(tripsLeft in 0..0xFFFF) { "tripsLeft out of UInt16 range: $tripsLeft" }
        val buf = ByteArray(TOTAL_BYTES)

        // Block 0 [0..3]: "VCM1" magic
        buf[MAGIC_OFFSET] = MAGIC[0]
        buf[MAGIC_OFFSET + 1] = MAGIC[1]
        buf[MAGIC_OFFSET + 2] = MAGIC[2]
        buf[MAGIC_OFFSET + 3] = MAGIC[3]
        // Block 0 [4..5]: bitmask LE
        buf[BITMASK_OFFSET] = (bitmask and 0xFF).toByte()
        buf[BITMASK_OFFSET + 1] = ((bitmask shr 8) and 0xFF).toByte()
        // Block 0 [10..11]: tripsLeft UInt16 LE (промпт 014)
        buf[TRIPS_OFFSET] = (tripsLeft and 0xFF).toByte()
        buf[TRIPS_OFFSET + 1] = ((tripsLeft shr 8) and 0xFF).toByte()
        // Block 1 [CARD_ID_OFFSET..+15]: cardId UUID v7 binary
        val cardIdBytes = uuidToBytes(cardId)
        System.arraycopy(cardIdBytes, 0, buf, CARD_ID_OFFSET, UUID_BYTE_LEN)
        // Block 2 [ENTITY_OFFSET..+15]: entityUuid UUID binary or zero
        val entityBytes = if (entity?.id != null) uuidToBytes(entity.id) else ByteArray(UUID_BYTE_LEN)
        System.arraycopy(entityBytes, 0, buf, ENTITY_OFFSET, UUID_BYTE_LEN)
        return buf
    }

    /**
     * Optional pre-fill: ренерирует cardId как UUID v7 если null/empty. Используется
     * server-call для convenience: терминал генерирует placeholder.
     */
    fun withGeneratedCardId(): CardIdentityVcm1 =
        if (this.cardId.toString().isNotBlank()) this
        else copy(cardId = UuidCreator.getTimeOrderedEpoch())

    companion object {
        const val TOTAL_BYTES = 48                  // 3 blocks × 16 bytes
        const val BLOCK_SIZE = 16
        const val USED_BLOCK_COUNT = 3               // sector 1 only
        const val MAGIC_OFFSET = 0
        const val MAGIC_SIZE = 4
        const val BITMASK_OFFSET = 4
        const val BITMASK_SIZE = 2
        const val TRIPS_OFFSET = 10                  // block 0 [10..11] — tripsLeft UInt16 LE (промпт 014)
        const val TRIPS_SIZE = 2
        const val CARD_ID_OFFSET = 16                // block 1
        const val CARD_ID_SIZE = 16
        const val ENTITY_OFFSET = 32                 // block 2
        const val ENTITY_SIZE = 16
        const val UUID_BYTE_LEN = 16
        val MAGIC = "VCM1".toByteArray(Charsets.US_ASCII)

        /**
         * Парсит block-данные sector 1 (48 байт) обратно в CardIdentityVcm1.
         * Если magic != "VCM1" → null (SAC1/old card или factory blank).
         */
        fun decodeFromBytes(buf: ByteArray): CardIdentityVcm1? {
            if (buf.size < TOTAL_BYTES) return null
            if (!buf.copyOfRange(MAGIC_OFFSET, MAGIC_OFFSET + MAGIC_SIZE).contentEquals(MAGIC)) {
                return null
            }
            val bitmask = ((buf[BITMASK_OFFSET].toInt() and 0xFF)) or
                ((buf[BITMASK_OFFSET + 1].toInt() and 0xFF) shl 8)
            if (bitmask !in 0..0x3FFF) return null
            val tripsLeft = ((buf[TRIPS_OFFSET].toInt() and 0xFF)) or
                ((buf[TRIPS_OFFSET + 1].toInt() and 0xFF) shl 8)
            val cardId = uuidFromBytes(buf, CARD_ID_OFFSET)
            val entityBytes = buf.copyOfRange(ENTITY_OFFSET, ENTITY_OFFSET + ENTITY_SIZE)
            val entity = if (entityBytes.all { it == 0x00.toByte() }) null
            else {
                val type = EntityType.forAsopCardTypeOrdinal(lowestSetBitOrdinal(bitmask))
                    ?: return null
                EntityRef(
                    type = type,
                    id = uuidFromBytes(entityBytes, 0)
                )
            }
            return CardIdentityVcm1(cardId = cardId, bitmask = bitmask, entity = entity, tripsLeft = tripsLeft)
        }

        /** UUID v7 generation helper (terminal-side placeholder). */
        fun generateCardId(): UUID = UuidCreator.getTimeOrderedEpoch()

        private fun uuidToBytes(uuid: UUID): ByteArray {
            val msb = uuid.mostSignificantBits
            val lsb = uuid.leastSignificantBits
            val bytes = ByteArray(UUID_BYTE_LEN)
            for (i in 0..7) bytes[i] = (msb ushr ((7 - i) * 8) and 0xFF).toByte()
            for (i in 0..7) bytes[8 + i] = (lsb ushr ((7 - i) * 8) and 0xFF).toByte()
            return bytes
        }

        private fun uuidFromBytes(buf: ByteArray, offset: Int): UUID {
            var msb = 0L
            var lsb = 0L
            for (i in 0..7) msb = (msb shl 8) or (buf[offset + i].toLong() and 0xFF)
            for (i in 0..7) lsb = (lsb shl 8) or (buf[offset + 8 + i].toLong() and 0xFF)
            return UUID(msb, lsb)
        }

        private fun lowestSetBitOrdinal(bitmask: Int): Int {
            for (i in 0..13) if (((bitmask shr i) and 1) == 1) return i
            return -1
        }
    }
}
