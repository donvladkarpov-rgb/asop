package ru.asop.card.dto

import java.util.UUID

/**
 * Запрос ручной блокировки/разблокировки карты в стоп-листе (промпт 016 §3.1.6).
 *
 * @param blockType `PERMANENT` (ручной/фрод) или `NEGATIVE_BALANCE` (авто по долгу).
 * @param relatedDebtId id открытого долга, с которым связана блокировка (опционально).
 * @param autoUnblockOnRecovery автоматически снять блок при погашении долга.
 */
data class BlacklistBlockRequest(
    val cardId: UUID,
    val blockType: String,
    val relatedDebtId: UUID? = null,
    val autoUnblockOnRecovery: Boolean = false
)