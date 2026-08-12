package ru.asop.terminal.activation

import java.util.UUID

/**
 * Entity-reference: связывает роль карты с конкретным ASOP-sущностью (user),
 * хранится в byte-блоке карты (block 2 = entityUuid).
 *
 * Промпт 009 (clean-break): только 2 значения — USER и NONE.
 * Все остальные entityType (regionId/carrierId/КРС/...) больше НЕ пишутся на карту:
 * routing выполняется через JOIN ASOP_CARD → ASOP_USER_* по USER_ID.
 */
data class EntityRef(
    val type: EntityType,
    val id: UUID?
)

/**
 * Промпт 009: минимальный enum. После clean-break единственный валидный entityType на карте = USER.
 * NONE — единственный случай для PASSENGER_ANONYMOUS.
 */
enum class EntityType(val fieldName: String, val serverValue: String) {
    USER("userId", "userId"),
    NONE("none", "none");

    fun isAllZeros(uid: ByteArray?): Boolean {
        if (uid == null || uid.isEmpty()) return true
        return uid.all { it == 0.toByte() }
    }

    companion object {
        /**
         * Промпт 009: маппинг AsopCardType → EntityType. После clean-break все роли кроме
         * `PASSENGER_ANONYMOUS` → USER. Раньше были разные entityType для разных ролей,
         * теперь signalling убран — carrier/org/region берутся через ASOP_USER_* по userId.
         */
        fun forAsopCardTypeOrdinal(ordinal: Int): EntityType? = when (ordinal) {
            13 -> NONE        // PASSENGER_ANONYMOUS: no entity
            else -> USER      // остальные 13 ординалов
        }

        fun fromFieldName(name: String): EntityType? =
            entries.firstOrNull { it.fieldName == name }
    }
}
