package ru.asop.api.gateway.dto.request

import java.util.UUID

/**
 * Запрос полной выгрузки справочников (первичная синхронизация) от терминала.
 * mTLS (chain Order 1).
 */
data class FullSyncRequest(
    val terminalId: UUID
)
