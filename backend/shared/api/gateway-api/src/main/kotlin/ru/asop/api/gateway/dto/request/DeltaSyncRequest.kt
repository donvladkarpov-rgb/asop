package ru.asop.api.gateway.dto.request

import java.time.Instant
import java.util.UUID

/**
 * Запрос инкрементальной дельта-синхронизации справочников от терминала.
 * mTLS (chain Order 1). Gateway доверяет terminalId (терминал знает свой id).
 *
 * @param terminalId UUID терминала (из SyncPreferences терминала)
 * @param lastUpdatedAt таблица → последняя точка синхронизации (ISO-8601)
 */
data class DeltaSyncRequest(
    val terminalId: UUID,
    val lastUpdatedAt: Map<String, Instant> = emptyMap()
)
