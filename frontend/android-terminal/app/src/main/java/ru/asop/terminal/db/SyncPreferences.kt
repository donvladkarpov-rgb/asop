package ru.asop.terminal.db

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
        private val KEY_DELTA_JOBS_ENABLED = booleanPreferencesKey("delta_jobs_enabled")
        private val KEY_SERVER_PUBLIC_KEY = stringPreferencesKey("server_public_key")

        // VCM1 (промпт 008): последняя карта, приложенная к терминалу. Используется как контекст
        // для sync-команд: server-side cardauth whitelist (uid, cardId).
        private val KEY_LAST_CARD_UID = stringPreferencesKey("last_card_uid")
        private val KEY_LAST_CARD_ID = stringPreferencesKey("last_card_id")
        private val KEY_LAST_CARD_TAP_TIME = longPreferencesKey("last_card_tap_time")
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
}
