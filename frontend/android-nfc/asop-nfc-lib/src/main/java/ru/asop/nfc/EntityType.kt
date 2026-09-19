package ru.asop.nfc

import java.util.UUID

/**
 * Entity-reference: связывает роль карты с конкретной ASOP-сущностью (user),
 * хранится в byte-блоке карты (block 2 = entityUuid).
 *
 * После clean-break (промпт 009) только 2 значения — USER и NONE. Все остальные
 * entityType (regionId/carrierId/КРС/...) больше НЕ пишутся на карту: routing
 * выполняется через JOIN ASOP_CARD → ASOP_USER_* по USER_ID.
 */
data class EntityRef(
    val type: EntityType,
    val id: UUID?
)

/**
 * Промпт 009: минимальный enum. Единственный валидный entityType на карте = USER.
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
        fun forAsopCardTypeOrdinal(ordinal: Int): EntityType? = when (ordinal) {
            13 -> NONE        // PASSENGER_ANONYMOUS: no entity
            else -> USER      // остальные 13 ординалов
        }

        fun fromFieldName(name: String): EntityType? =
            entries.firstOrNull { it.fieldName == name }
    }
}