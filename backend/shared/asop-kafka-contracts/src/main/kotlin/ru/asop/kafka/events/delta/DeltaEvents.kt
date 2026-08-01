package ru.asop.kafka.events.delta

import java.time.Instant
import java.util.UUID

/**
 * Команда дельта-синхронизации справочников.
 * Gateway → asop.delta.commands → orchestrator-service.
 * Таблицы извлекаются по (carrierId, regionId) с UPDATED_AT > lastUpdatedAt.
 */
data class DeltaSyncCommand(
    val eventId: UUID,
    val terminalId: UUID,
    val carrierId: UUID? = null,
    val regionId: UUID? = null,
    val lastUpdatedAt: Map<String, Instant> // tableName → lastUpdatedAt
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
