package ru.asop.api.gateway.dto.request

import java.util.UUID

/**
 * Запрос инкрементальной дельта-синхронизации справочников от терминала.
 * mTLS (chain Order 1). Gateway доверяет terminalId (терминал знает свой id).
 *
 * @param terminalId UUID терминала (из SyncPreferences терминала)
 * @param carrierId UUID перевозчика (из SyncPreferences, nullable)
 * @param regionId UUID региона (из SyncPreferences, nullable)
 * @param lastVersion последний глобальный VERSION-курсор (sequence), nullable
 */
data class DeltaSyncRequest(
    val terminalId: UUID,
    val carrierId: UUID? = null,
    val regionId: UUID? = null,
    val lastVersion: Long? = null
)
