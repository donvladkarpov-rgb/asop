package ru.asop.terminal.worker

import kotlinx.coroutines.flow.first
import org.json.JSONObject
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.db.dao.ReferenceRowDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Параметры профиля терминала (ASOP_TERMINAL_PROFILES → reference_rows.asop_terminal_profiles).
 * Интервалы фоновых воркеров в мс. Значения по умолчанию — если профиль не задан
 * (терминал ещё не зарегистрирован / дельта с профилем ещё не доехала).
 */
data class TerminalProfileParams(
    val syncIntervalMs: Long = DEFAULT_SYNC_INTERVAL_MS,
    val eventPollIntervalMs: Long = DEFAULT_EVENT_POLL_INTERVAL_MS,
    val deltaSyncIntervalMs: Long = DEFAULT_DELTA_SYNC_INTERVAL_MS,
    val deltaPollIntervalMs: Long = DEFAULT_DELTA_POLL_INTERVAL_MS,
    val watermarkIntervalMs: Long = DEFAULT_WATERMARK_INTERVAL_MS
) {
    companion object {
        const val DEFAULT_SYNC_INTERVAL_MS = 10_000L
        const val DEFAULT_EVENT_POLL_INTERVAL_MS = 5_000L
        const val DEFAULT_DELTA_SYNC_INTERVAL_MS = 10_000L
        const val DEFAULT_DELTA_POLL_INTERVAL_MS = 5_000L
        const val DEFAULT_WATERMARK_INTERVAL_MS = 30_000L

        val DEFAULTS = TerminalProfileParams()
    }
}

/**
 * Читает параметры профиля терминала из локальных справочников (дельта-синк).
 * Профиль терминала — из SyncPreferences (terminalProfileId, заполняется при регистрации).
 * Сама строка профиля — reference_rows: table_name="asop_terminal_profiles", row_id=profileId
 * (rowId = первый proto-поле profile_id), payload JsonFormat (camelCase):
 *   {"profileId": "...", "profileName": "...", "profileParams": "{\"syncIntervalMs\":...}", "isBase": true, ...}
 *
 * Фолбэк на [TerminalProfileParams.DEFAULTS] при любой ошибке/отказе (профиль не синкнут,
 * JSON битый) — воркеры всегда имеют валидные интервалы.
 */
@Singleton
class TerminalProfileProvider @Inject constructor(
    private val syncPreferences: SyncPreferences,
    private val referenceRowDao: ReferenceRowDao
) {

    suspend fun params(): TerminalProfileParams {
        return runCatching { resolve() }.getOrElse { TerminalProfileParams.DEFAULTS }
    }

    private suspend fun resolve(): TerminalProfileParams {
        val profileId = syncPreferences.terminalProfileId.first()
            ?: return TerminalProfileParams.DEFAULTS
        val payload = referenceRowDao.rawPayloadById(PROFILE_TABLE, profileId)
            ?: return TerminalProfileParams.DEFAULTS
        return parseParams(payload)
            ?: TerminalProfileParams.DEFAULTS
    }

    private fun parseParams(payload: String): TerminalProfileParams? {
        val profile = JSONObject(payload)
        val paramsJson = profile.optString("profileParams", "").ifBlank { return null }
        val params = JSONObject(paramsJson)
        return TerminalProfileParams(
            syncIntervalMs = params.optLong("syncIntervalMs", TerminalProfileParams.DEFAULT_SYNC_INTERVAL_MS),
            eventPollIntervalMs = params.optLong("eventPollIntervalMs", TerminalProfileParams.DEFAULT_EVENT_POLL_INTERVAL_MS),
            deltaSyncIntervalMs = params.optLong("deltaSyncIntervalMs", TerminalProfileParams.DEFAULT_DELTA_SYNC_INTERVAL_MS),
            deltaPollIntervalMs = params.optLong("deltaPollIntervalMs", TerminalProfileParams.DEFAULT_DELTA_POLL_INTERVAL_MS),
            watermarkIntervalMs = params.optLong("watermarkIntervalMs", TerminalProfileParams.DEFAULT_WATERMARK_INTERVAL_MS)
        )
    }

    companion object {
        const val PROFILE_TABLE = "asop_terminal_profiles"
    }
}