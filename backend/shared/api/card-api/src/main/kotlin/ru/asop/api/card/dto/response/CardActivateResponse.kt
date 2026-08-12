package ru.asop.api.card.dto.response

import java.time.Instant
import java.util.UUID

/**
 * Ответ для Classic VCM1-flow. Серверный masterCardId может отличаться от clientCardId
 * (если карта уже активирована и в БД записан другой serverCardId). Терминал ОБЯЗАН
 * перезаписать block 1 на карте при `serverCardId != clientCardId` (либо отказаться).
 */
data class ClassicCardActivateResponse(
    /** UUID, присвоенный сервером (master authority). Может отличаться от clientCardId. */
    val cardId: UUID,
    /**
     * true если серверный cardId отличается от того, что был на карте при чтении.
     * Терминал при `true` ДОЛЖЕН перезаписать block 1 новым cardId.
     */
    val cardIdOverridden: Boolean,
    val bitmask: Int,
    val entityType: String?,
    val entityId: UUID?,
    val registeredAt: Instant
)

data class CardActivateResponse(
    val cardId: UUID,
    val cardRole: String,
    val registeredAt: Instant,
    val vcm1: ClassicCardActivateResponse? = null
)
