package ru.asop.terminal.activation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.asop.nfc.AsopCardType
import ru.asop.nfc.CardIdentityVcm1
import ru.asop.nfc.EntityRef
import ru.asop.nfc.EntityType
import java.util.UUID

/**
 * Unit tests для VCM1 encode/decode (промпт 008).
 *
 * Coverage:
 *  - Roundtrip enc/dec для разных bitmask и entity-UUIDs.
 *  - All-zeros entity проверка (PASSENGER_ANONYMOUS).
 *  - Multi-role: highest-bit-wins (lowest ordinal).
 *  - Rejection of garbage / unsupported bitmask.
 *  - Rejection of wrong magic.
 */
class CardIdentityVcm1Test {

    @Test
    fun roundtrip_superAdmin_simple() {
        val cardId = UUID.fromString("019ff289-aaaa-7bbb-cccc-dddddddddddd")
        val vcm1 = CardIdentityVcm1(
            cardId = cardId,
            bitmask = 0x0001,  // SUPER_ADMIN only
            entity = EntityRef(EntityType.USER, UUID.fromString("019ff289-1111-7111-8111-111111111111"))
        )
        val buf = vcm1.encodeAsBytes()
        assertEquals(CardIdentityVcm1.TOTAL_BYTES, buf.size)

        val bytes = buf.copyOfRange(0, 4)
        assertTrue(bytes[0] == 'V'.code.toByte())
        assertTrue(bytes[1] == 'C'.code.toByte())
        assertTrue(bytes[2] == 'M'.code.toByte())
        assertTrue(bytes[3] == '1'.code.toByte())

        // Bitmask LE
        assertEquals(0x01.toByte(), buf[CardIdentityVcm1.BITMASK_OFFSET])
        assertEquals(0x00.toByte(), buf[CardIdentityVcm1.BITMASK_OFFSET + 1])

        val decoded = CardIdentityVcm1.decodeFromBytes(buf)
        assertNotNull(decoded)
        assertEquals(cardId, decoded!!.cardId)
        assertEquals(0x0001, decoded.bitmask)
        assertNotNull(decoded.entity)
        assertEquals(EntityType.USER, decoded.entity!!.type)
    }

    @Test
    fun roundtrip_passengerAnonymous_zeroEntity() {
        val cardId = UUID.fromString("019ff289-aaaa-7bbb-cccc-eeeeeeeeeeee")
        val vcm1 = CardIdentityVcm1(
            cardId = cardId,
            bitmask = 0x2000,  // PASSENGER_ANONYMOUS bit 13
            entity = null
        )
        val buf = vcm1.encodeAsBytes()
        // Block 2 (entity) should be all-zeros for anonymous card
        val entityBytes = buf.copyOfRange(CardIdentityVcm1.ENTITY_OFFSET, CardIdentityVcm1.ENTITY_OFFSET + 16)
        assertTrue("entity block should be all zeros for PASSENGER_ANONYMOUS",
            entityBytes.all { it == 0x00.toByte() })

        val decoded = CardIdentityVcm1.decodeFromBytes(buf)
        assertNotNull(decoded)
        assertEquals(0x2000, decoded!!.bitmask)
        assertNull(decoded.entity)
    }

    @Test
    fun roundtrip_multiRole_highestBitWins() {
        // Bitmask = 0x0241 = bits 0, 6, 9 set:
        //   bit 0 (SUPER_ADMIN) — highest priority
        //   bit 6 (CARRIER_DISPATCHER) — carrierId
        //   bit 9 (DRIVER) — carrierId
        // All carrierId → single entity in CARRIER.
        // Highest-set bit (lowest ordinal) = bit 0 = SUPER_ADMIN → userId entity.
        val cardId = UUID.fromString("019ff289-aaaa-7bbb-cccc-ffffffffff01")
        val vcm1 = CardIdentityVcm1(
            cardId = cardId,
            bitmask = 0x0241,
            entity = EntityRef(EntityType.USER, UUID.fromString("019ff289-2222-7222-8222-222222222222"))
        )
        val decoded = CardIdentityVcm1.decodeFromBytes(vcm1.encodeAsBytes())
        assertNotNull(decoded)
        assertEquals(0x0241, decoded!!.bitmask)
        assertEquals(3, decoded.bitmask.countOneBits())  // bits 0, 6, 9
        assertEquals(0, java.lang.Integer.numberOfTrailingZeros(decoded.bitmask))  // first set bit at position 0 = SUPER_ADMIN
        assertEquals(EntityType.USER, decoded.entity!!.type)
    }

    @Test
    fun roundtrip_carrierId_dispatcherDriver_merge() {
        // Bits 6 + 9 = carrierId family → primary = CARRIER_DISPATCHER (lower ordinal), single entity
        // Промпт 009 clean-break: entityType на карте только USER (routing по userId).
        val vcm1 = CardIdentityVcm1(
            cardId = UUID.randomUUID(),
            bitmask = 0x0240,  // bits 6 (DISPATCHER) + 9 (DRIVER) but NOT 0
            entity = EntityRef(EntityType.USER, UUID.randomUUID())
        )
        val decoded = CardIdentityVcm1.decodeFromBytes(vcm1.encodeAsBytes())
        assertEquals(0x0240, decoded!!.bitmask)
        assertEquals(2, decoded.bitmask.countOneBits())
        assertEquals(EntityType.USER, decoded.entity!!.type)
    }

    @Test
    fun decode_rejectsGarbage() {
        val garbage = ByteArray(48)
        // No "VCM1" magic at [0..3]
        assertNull(CardIdentityVcm1.decodeFromBytes(garbage))

        // Wrong magic "SAC1" → null (legacy format)
        val sac1Magic = "SAC1".toByteArray(Charsets.US_ASCII)
        val sac1Bytes = ByteArray(48)
        System.arraycopy(sac1Magic, 0, sac1Bytes, 0, 4)
        assertNull(CardIdentityVcm1.decodeFromBytes(sac1Bytes))

        // Wrong size (короткий буфер)
        val short = "VCM1".toByteArray(Charsets.US_ASCII) + ByteArray(20)
        assertNull(CardIdentityVcm1.decodeFromBytes(short))
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejectsBitmaskOutOfRange() {
        val vcm1 = CardIdentityVcm1(
            cardId = UUID.randomUUID(),
            bitmask = 0x4000,  // bit 14 — reserved, out of 0..0x3FFF
            entity = null
        )
        vcm1.encodeAsBytes()
    }

    @Test
    fun byteOrder_uuidv7_highBit() {
        // UUID v7 имеет первые 48 бит = unix ms timestamp. Первая byte должна иметь
        // старший бит установлен. Проверяем что мы сохранили MSB-first.
        val vcm7 = UUID.fromString("019ff289-aaaa-7bbb-cccc-dddddddddddd").toString()
        val u = UUID.fromString(vcm7)
        val msb = u.mostSignificantBits
        // Constructor: clears bits indicating UUID version + variant.
        // version = 7 → bits 12-15 of MSB = 0111xxxx → ожидаем upper 4 bits of first 16 MSB bits = 0111
        // Проверим через byte conversion.
        val vcm1 = CardIdentityVcm1(
            cardId = u,
            bitmask = 1,  // SUPER_ADMIN
            entity = null
        )
        val buf = vcm1.encodeAsBytes()
        // Block 1 cardId в MSB-first
        for (i in 0..7) {
            val byte = buf[CardIdentityVcm1.CARD_ID_OFFSET + i]
            val origByte = (msb shr ((7 - i) * 8)) and 0xFF
            assertEquals("byte offset $i: stored=$byte, expected=$origByte (MSB-first)",
                origByte.toByte(), byte)
        }
    }

    @Test
    fun primaryRoleForBitmask_picksLowestOrdinal() {
        // lowest set bit (самый старший в иерархии) → primary role
        val disp = AsopCardType.CARRIER_DISPATCHER   // ordinal 7
        val driver = AsopCardType.DRIVER             // ordinal 10
        val mask = AsopCardType.bitmaskForEntityRoles(listOf(driver, disp))
        assertEquals(disp, AsopCardType.highestSetBitRole(mask))

        val admin = AsopCardType.CARRIER_ADMIN       // ordinal 4
        val anon = AsopCardType.PASSENGER_ANONYMOUS  // ordinal 13
        val m2 = AsopCardType.bitmaskForEntityRoles(listOf(admin, anon))
        assertEquals(admin, AsopCardType.highestSetBitRole(m2))

        // bitmask = 0 → null
        assertNull(AsopCardType.highestSetBitRole(0))
        // только PASSENGER_ANONYMOUS → ordinal 13
        assertEquals(AsopCardType.PASSENGER_ANONYMOUS,
            AsopCardType.highestSetBitRole(AsopCardType.bitmaskForEntityRoles(listOf(anon))))
    }

    @Test
    fun entityTypeForCardType_consistency() {
        // Промпт 009 clean-break: все роли кроме PASSENGER_ANONYMOUS → USER.
        // Раньше (pre-009) было CARRIER/AUDIT_SERVICE — теперь только USER/NONE.
        assertEquals(EntityType.USER, EntityType.forAsopCardTypeOrdinal(AsopCardType.DRIVER.ordinal))
        assertEquals(EntityType.USER, EntityType.forAsopCardTypeOrdinal(AsopCardType.CARRIER_DISPATCHER.ordinal))
        assertEquals(EntityType.USER, EntityType.forAsopCardTypeOrdinal(AsopCardType.CARRIER_ADMIN.ordinal))

        // KRS-роли также → USER (routing через ASOP_USER_KRS по userId)
        assertEquals(EntityType.USER, EntityType.forAsopCardTypeOrdinal(AsopCardType.KRS_ADMIN.ordinal))
        assertEquals(EntityType.USER, EntityType.forAsopCardTypeOrdinal(AsopCardType.KRS_CONTROLLER.ordinal))

        // PASSENGER_ANONYMOUS → NONE (no entity)
        assertEquals(EntityType.NONE, EntityType.forAsopCardTypeOrdinal(AsopCardType.PASSENGER_ANONYMOUS.ordinal))
    }
}
