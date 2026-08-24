package ru.asop.api.card.dto.response

import java.time.Instant
import java.util.UUID

data class CardResponse(
    val id: UUID,
    val cardTypeId: UUID,
    val userId: UUID?,
    /** ASOP_CARDS.CARRIER_ID — привязка карты к перевозчику (для web-admin фильтра по carrierId). */
    val carrierId: UUID? = null,
    /** Если true — UID указывает на MIFARE Classic карту, иначе DESfire. */
    val isClassic: Boolean = false,
    val isPrimary: Boolean,
    /**
     * VCM1 bitmask (промпт 008) для Classic карт. Extracted из
     * ASOP_CARD_MIFARES.IDENTITY_JSON (json field "bitmask", numeric).
     * null для DESfire/PKCS cards или Classic VCM1 without auth.
     */
    val bitmask: Int? = null,
    val registeredAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** UID NFC-карты (hex) из ASOP_CARD_MIFARES.UID. null если карта не MIFARE. */
    val uid: String? = null,
    /** Технология NFC-карты (промпт 007): "DESFIRE" | "CLASSIC". null если не определена. */
    val cardTech: String? = null,
    /** ASOP_CARD_MIFARES.CARD_ROLE (14 ролей АСОП). null для не-MIFARE карт. */
    val cardRole: String? = null,
    /** Остаток поездок (VCM1 UInt16; ASOP_CARD_MIFARES.TRIPS_LEFT). Обновляется терминальными транзакциями. */
    val tripsLeft: Int? = null,
    /** ASOP_CARD_TYPES.CARD_TYPE_NAME (напр. "MIFARE DESFire"). */
    val cardTypeName: String? = null,
    /** ФИО владельца из ASOP_USERS (join по USER_ID). null для анонимных карт. */
    val holderName: String? = null,
    /** Окончание действия карты (ASOP_CARD_MIFARES.VALID_UNTIL). */
    val validUntil: Instant? = null,
    /** Дата отзыва/блокировки (ASOP_CARD_MIFARES.REVOKED_AT). */
    val revokedAt: Instant? = null,
    /**
     * Производный статус для web-admin:
     * "deleted" (ASOP_CARDS.DELETED_AT) → "blocked" (REVOKED_AT) → "expired" (VALID_UNTIL < now) → "active".
     */
    val status: String? = null
)
