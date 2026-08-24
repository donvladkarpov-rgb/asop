package ru.asop.kafka.events.delta

import java.util.UUID

/**
 * Команда дельта-синхронизации справочников.
 * Gateway → asop.delta.commands → orchestrator-service.
 * Таблицы извлекаются по (carrierId, regionId) с VERSION > lastVersion
 * (глобальный sequence-курсор asop_delta_version_seq).
 */
data class DeltaSyncCommand(
    val eventId: UUID,
    val terminalId: UUID,
    val carrierId: UUID? = null,
    val regionId: UUID? = null,
    // Налэбл-фильтры персональных карт по привязкам пользователя (user_krs /
    // user_cards_distributors). Пробрасываются в admin-users/delta → userIdsIn →
    // дельты карт (терминал получает только персональные карты своего scope).
    val auditServiceId: UUID? = null,
    val cardsDistributorId: UUID? = null,
    val lastVersion: Long? = null
)

/**
 * Команда полной выгрузки справочников (первичная синхронизация).
 * Gateway → asop.delta.full.commands → orchestrator-service.
 * Результат — pre-signed S3 URL (MinIO) полной выгрузки.
 */
data class FullSyncCommand(
    val eventId: UUID,
    val terminalId: UUID,
    val carrierId: UUID? = null,
    val regionId: UUID? = null
)
