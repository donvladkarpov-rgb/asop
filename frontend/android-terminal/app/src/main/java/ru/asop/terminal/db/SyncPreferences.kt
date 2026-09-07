package ru.asop.terminal.db

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStore by preferencesDataStore(name = "sync_preferences")

class SyncPreferences(private val context: Context) {

    companion object {
        private val KEY_LAST_SYNC_TIME = longPreferencesKey("last_sync_time")
        private val KEY_CURRENT_SESSION_ID = stringPreferencesKey("current_session_id")
        private val KEY_SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        private val KEY_TERMINAL_ID = stringPreferencesKey("terminal_id")
        private val KEY_CARRIER_ID = stringPreferencesKey("carrier_id")
        private val KEY_REGION_ID = stringPreferencesKey("region_id")
        private val KEY_TIMEZONE = stringPreferencesKey("timezone")
        private val KEY_TERMINAL_PROFILE_ID = stringPreferencesKey("terminal_profile_id")
        private val KEY_DELTA_JOBS_ENABLED = booleanPreferencesKey("delta_jobs_enabled")
        private val KEY_SERVER_PUBLIC_KEY = stringPreferencesKey("server_public_key")

        // VCM1 (промпт 008): последняя карта, приложенная к терминалу. Используется как контекст
        // для sync-команд: server-side cardauth whitelist (uid, cardId).
        private val KEY_LAST_CARD_UID = stringPreferencesKey("last_card_uid")
        private val KEY_LAST_CARD_ID = stringPreferencesKey("last_card_id")
        private val KEY_LAST_CARD_TAP_TIME = longPreferencesKey("last_card_tap_time")

        // Промпт 012: per-terminal event seq watermark.
        // KEY_NEXT_EVENT_SEQ — следующий seq для инкрементного назначения при INSERT в pending_events.
        // KEY_LAST_EVENT_SEQ  — локально последний назначенный seq (для информации/диагностики).
        // KEY_WATERMARK_FROM_SERVER — server lastSeq запрошенный через GET /api/v1/terminals/{id}/event-watermark.
        //                                Используется для catch-up: если server's lastSeq > локальный nextSeq,
        //                                то терминал пересинхронизирует (re-send pending events).
        private val KEY_NEXT_EVENT_SEQ = longPreferencesKey("next_event_seq")
        private val KEY_LAST_EVENT_SEQ = longPreferencesKey("last_event_seq")
        private val KEY_WATERMARK_FROM_SERVER = longPreferencesKey("watermark_from_server")

        // Промпт 015: debug mock GPS (предзаписанный маршрут вместо FusedLocationProviderClient)
        private val KEY_DEBUG_GPS = booleanPreferencesKey("debug_gps")
        // Промпт 015: GPS tracking включён (switch state). Default: true для dev.
        private val KEY_GPS_ENABLED = booleanPreferencesKey("gps_enabled")
    }

    val lastSyncTime: Flow<Long?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNC_TIME]
    }

    val currentSessionId: Flow<String?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_CURRENT_SESSION_ID]
    }

    val syncEnabled: Flow<Boolean> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_SYNC_ENABLED] ?: true
    }

    /** Выполняются ли дельта/full-dump джобы (WorkManager) сейчас. Default: true. */
    val deltaJobsEnabled: Flow<Boolean> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_DELTA_JOBS_ENABLED] ?: true
    }

    val terminalId: Flow<String?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_TERMINAL_ID]
    }

    val carrierId: Flow<String?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_CARRIER_ID]
    }

    val regionId: Flow<String?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_REGION_ID]
    }

    val timezone: Flow<String?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_TIMEZONE]
    }

    /** Профиль настроек терминала (ASOP_TERMINAL_PROFILES) — интервалы воркеров. */
    val terminalProfileId: Flow<String?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_TERMINAL_PROFILE_ID]
    }

    /** Блокирующее чтение (non-suspend контексты). */
    fun terminalProfileIdSync(): String? = kotlinx.coroutines.runBlocking {
        context.syncDataStore.data.first()[KEY_TERMINAL_PROFILE_ID]
    }

    suspend fun setTerminalProfileId(id: String?) {
        context.syncDataStore.edit { prefs ->
            if (id != null) prefs[KEY_TERMINAL_PROFILE_ID] = id
            else prefs.remove(KEY_TERMINAL_PROFILE_ID)
        }
    }

    val serverPublicKey: Flow<String?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_SERVER_PUBLIC_KEY]
    }

    val lastCardUid: Flow<String?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_LAST_CARD_UID]
    }

    val lastCardId: Flow<String?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_LAST_CARD_ID]
    }

    val lastCardTapTime: Flow<Long?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_LAST_CARD_TAP_TIME]
    }

    /**
     * Сохраняет контекст последнего tap карты (uid, cardId). Используется чтобы
     * sync-* команды (transaction, session close, GPS, etc.) могли НЕСТИ
     * cardId для server-side whitelist (uid, cardId).
     */
    suspend fun setLastCardTap(uid: String?, cardId: String?) {
        if (uid == null && cardId == null) {
            context.syncDataStore.edit { it.remove(KEY_LAST_CARD_UID); it.remove(KEY_LAST_CARD_ID) }
            return
        }
        context.syncDataStore.edit { prefs ->
            if (uid != null) prefs[KEY_LAST_CARD_UID] = uid
            else prefs.remove(KEY_LAST_CARD_UID)
            if (cardId != null) prefs[KEY_LAST_CARD_ID] = cardId
            else prefs.remove(KEY_LAST_CARD_ID)
            prefs[KEY_LAST_CARD_TAP_TIME] = System.currentTimeMillis()
        }
    }

    suspend fun setLastSyncTime(time: Long) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC_TIME] = time
        }
    }

    suspend fun setCurrentSessionId(id: String?) {
        context.syncDataStore.edit { prefs ->
            if (id != null) prefs[KEY_CURRENT_SESSION_ID] = id
            else prefs.remove(KEY_CURRENT_SESSION_ID)
        }
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setDeltaJobsEnabled(enabled: Boolean) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_DELTA_JOBS_ENABLED] = enabled
        }
    }

    suspend fun setTerminalId(id: String) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_TERMINAL_ID] = id
        }
    }

    suspend fun clearTerminalId() {
        context.syncDataStore.edit { prefs ->
            prefs.remove(KEY_TERMINAL_ID)
        }
    }

    suspend fun setCarrierId(id: String?) {
        context.syncDataStore.edit { prefs ->
            if (id != null) prefs[KEY_CARRIER_ID] = id
            else prefs.remove(KEY_CARRIER_ID)
        }
    }

    suspend fun setRegionId(id: String?) {
        context.syncDataStore.edit { prefs ->
            if (id != null) prefs[KEY_REGION_ID] = id
            else prefs.remove(KEY_REGION_ID)
        }
    }

    suspend fun setTimezone(tz: String?) {
        context.syncDataStore.edit { prefs ->
            if (tz != null) prefs[KEY_TIMEZONE] = tz
            else prefs.remove(KEY_TIMEZONE)
        }
    }

    suspend fun setServerPublicKey(pem: String) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_SERVER_PUBLIC_KEY] = pem
        }
    }

    // ===========================================
    // Промпт 012: per-terminal event seq watermark
    // ===========================================

    /**
     * Читает текущий nextSeq, инкрементирует и сохраняет. Каждое сообщение получает
     * уникальный монотонный seq — watermark на сервере гарантирует порядок применения.
     *
     * Если сервер ещё не синхронизирован с терминалом, возвращаемое значение начинается
     * с (max(server_lastSeq, текущий_nextSeq) + 1) — никогда не возвращаем seq меньше
     * уже подтверждённого сервером.
     */
    suspend fun nextSeq(): Long {
        var nextValue: Long = 1L
        context.syncDataStore.edit { prefs ->
            val current = prefs[KEY_NEXT_EVENT_SEQ] ?: 0L
            val serverWatermark = prefs[KEY_WATERMARK_FROM_SERVER] ?: 0L
            // Монотонный: max(server.known.lastSeq, local.next) + 1.
            val baseline = maxOf(current, serverWatermark + 1L)
            // Не возвращать <= last_event_seq (гарантия монотонности).
            val lastSent = prefs[KEY_LAST_EVENT_SEQ] ?: 0L
            val finalSeq = maxOf(baseline, lastSent + 1L)
            prefs[KEY_NEXT_EVENT_SEQ] = finalSeq
            prefs[KEY_LAST_EVENT_SEQ] = finalSeq
            nextValue = finalSeq
        }
        return nextValue
    }

    /**
     * Sync-версия nextSeq для вызова из non-suspend контекстов (e.g. Service.onLocationChanged).
     * Использует synchronized counter in-memory + DataStore commit при lifecycle events.
     */
    @Volatile private var cachedNextSeq: Long = -1L
    @Synchronized
    fun nextSeqSync(): Long {
        if (cachedNextSeq <= 0L) {
            // Lazy load from DataStore via runBlocking
            val initial = kotlinx.coroutines.runBlocking {
                var v = 0L
                context.syncDataStore.edit { prefs ->
                    val current = prefs[KEY_NEXT_EVENT_SEQ] ?: 0L
                    val serverWatermark = prefs[KEY_WATERMARK_FROM_SERVER] ?: 0L
                    v = maxOf(current, serverWatermark + 1L)
                    prefs[KEY_NEXT_EVENT_SEQ] = v
                }
                v
            }
            cachedNextSeq = initial
        }
        val next = cachedNextSeq
        cachedNextSeq = next + 1L
        return next
    }

    val lastSeq: Flow<Long?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_LAST_EVENT_SEQ]
    }

    val watermarkFromServer: Flow<Long?> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_WATERMARK_FROM_SERVER]
    }

    /** Промпт 015: включён ли mock GPS вместо FusedLocationProviderClient. Default: true (dev). */
    val isDebugGps: Flow<Boolean> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_DEBUG_GPS] ?: true
    }

    suspend fun setDebugGps(enabled: Boolean) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_DEBUG_GPS] = enabled
        }
    }

    /** Промпт 015: включён ли GPS-трекинг (persisted switch state). Default: true (dev). */
    val gpsEnabled: Flow<Boolean> = context.syncDataStore.data.map { prefs ->
        prefs[KEY_GPS_ENABLED] ?: true
    }

    suspend fun setGpsEnabled(enabled: Boolean) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_GPS_ENABLED] = enabled
        }
    }

    /**
     * Вызывается [WatermarkSyncWorker] после получения ответа от
     * `GET /api/v1/terminals/{id}/event-watermark`. Устанавливает baseline из
     * которого `nextSeq()` будет инкрементировать от `serverLastSeq + 1`,
     * гарантируя что терминал никогда не назначит seq меньше того, что сервер
     * уже обработал.
     */
    suspend fun setServerWatermark(serverLastSeq: Long) {
        context.syncDataStore.edit { prefs ->
            prefs[KEY_WATERMARK_FROM_SERVER] = serverLastSeq
            // Если локальный nextSeq оказался меньше server's lastSeq+1,
            // сбросить — иначе при следующем nextSeq() упадёт в baseline.
            val current = prefs[KEY_NEXT_EVENT_SEQ] ?: 0L
            if (current <= serverLastSeq) {
                prefs[KEY_NEXT_EVENT_SEQ] = serverLastSeq + 1L
            }
        }
    }
}
