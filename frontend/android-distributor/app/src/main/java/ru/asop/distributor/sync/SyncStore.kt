package ru.asop.distributor.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.first
import ru.asop.distributor.network.models.AsopKeyRow
import ru.asop.distributor.network.models.DistributorFiscalizerRow
import ru.asop.distributor.network.models.FiscalizerRow
import ru.asop.distributor.network.models.TariffRateRow

private val Context.syncDataStore by preferencesDataStore(name = "asop_distributor_sync")

/**
 * Хранение синхронизированных справочников дистрибьютора: ключи ASOP_KEYS (ciphertext),
 * тарифы ASOP_TARIFF_RATES и фискализаторы (ASOP_FISCALIZERS + ASOP_DISTRIBUTOR_FISCALIZERS)
 * + курсовые версии. Переживает рестарт приложения.
 */
class SyncStore(private val context: Context, private val moshi: Moshi) {

    private val keysAdapter = moshi.adapter<List<AsopKeyRow>>(
        Types.newParameterizedType(List::class.java, AsopKeyRow::class.java)
    )
    private val tariffsAdapter = moshi.adapter<List<TariffRateRow>>(
        Types.newParameterizedType(List::class.java, TariffRateRow::class.java)
    )
    private val fiscalizersAdapter = moshi.adapter<List<FiscalizerRow>>(
        Types.newParameterizedType(List::class.java, FiscalizerRow::class.java)
    )
    private val distributorFiscalizersAdapter = moshi.adapter<List<DistributorFiscalizerRow>>(
        Types.newParameterizedType(List::class.java, DistributorFiscalizerRow::class.java)
    )

    private val prefs = context.syncDataStore

    companion object {
        private val KEY_SERIAL = stringPreferencesKey("serial")
        private val KEY_DISTRIBUTOR_TERMINAL_ID = stringPreferencesKey("distributor_terminal_id")
        private val KEY_LAST_KEYS_VERSION = longPreferencesKey("last_keys_version")
        private val KEY_LAST_TARIFFS_VERSION = longPreferencesKey("last_tariffs_version")
        private val KEY_KEYS_JSON = stringPreferencesKey("keys_json")
        private val KEY_TARIFFS_JSON = stringPreferencesKey("tariffs_json")
        private val KEY_LAST_FISCALIZERS_VERSION = longPreferencesKey("last_fiscalizers_version")
        private val KEY_LAST_DISTR_FISCALIZERS_VERSION = longPreferencesKey("last_distr_fiscalizers_version")
        private val KEY_FISCALIZERS_JSON = stringPreferencesKey("fiscalizers_json")
        private val KEY_DISTR_FISCALIZERS_JSON = stringPreferencesKey("distr_fiscalizers_json")
        private val KEY_SYNCED_AT = longPreferencesKey("synced_at_ms")
    }

    suspend fun serial(): String? = prefs.data.first()[KEY_SERIAL]

    suspend fun setSerial(serial: String) = prefs.edit { it[KEY_SERIAL] = serial }

    suspend fun distributorTerminalId(): String? = prefs.data.first()[KEY_DISTRIBUTOR_TERMINAL_ID]

    suspend fun setDistributorTerminalId(id: String?) =
        prefs.edit { if (id != null) it[KEY_DISTRIBUTOR_TERMINAL_ID] = id else it.remove(KEY_DISTRIBUTOR_TERMINAL_ID) }

    suspend fun lastKeysVersion(): Long = prefs.data.first()[KEY_LAST_KEYS_VERSION] ?: 0L

    suspend fun lastTariffsVersion(): Long = prefs.data.first()[KEY_LAST_TARIFFS_VERSION] ?: 0L

    suspend fun lastFiscalizersVersion(): Long = prefs.data.first()[KEY_LAST_FISCALIZERS_VERSION] ?: 0L

    suspend fun lastDistributorFiscalizersVersion(): Long = prefs.data.first()[KEY_LAST_DISTR_FISCALIZERS_VERSION] ?: 0L

    suspend fun keys(): List<AsopKeyRow> = decodeKeys(prefs.data.first()[KEY_KEYS_JSON])

    suspend fun tariffs(): List<TariffRateRow> = decodeTariffs(prefs.data.first()[KEY_TARIFFS_JSON])

    suspend fun fiscalizers(): List<FiscalizerRow> = decodeFiscalizers(prefs.data.first()[KEY_FISCALIZERS_JSON])

    suspend fun distributorFiscalizers(): List<DistributorFiscalizerRow> =
        decodeDistributorFiscalizers(prefs.data.first()[KEY_DISTR_FISCALIZERS_JSON])

    suspend fun setKeysVersion(keysVersion: Long, tariffsVersion: Long) =
        prefs.edit {
            it[KEY_LAST_KEYS_VERSION] = keysVersion
            it[KEY_LAST_TARIFFS_VERSION] = tariffsVersion
        }

    suspend fun setFiscalizersVersion(fiscalizersVersion: Long, distributorFiscalizersVersion: Long) =
        prefs.edit {
            it[KEY_LAST_FISCALIZERS_VERSION] = fiscalizersVersion
            it[KEY_LAST_DISTR_FISCALIZERS_VERSION] = distributorFiscalizersVersion
        }

    /** Merge: новые/изменённые — заменить, soft-deleted — удалить, версии курсоров обновить. */
    suspend fun mergeKeys(rows: List<AsopKeyRow>) {
        val live = rows.filter { it.deletedAt == null }
        val alive = keys().filter { k -> live.none { it.keyId == k.keyId } }
        val merged = (alive + live)
            .distinctBy { it.keyId }
            .sortedByDescending { it.version ?: 0L }
        prefs.edit { it[KEY_KEYS_JSON] = keysAdapter.toJson(merged) }
    }

    suspend fun mergeTariffs(rows: List<TariffRateRow>) {
        val live = rows.filter { it.deletedAt == null }
        val alive = tariffs().filter { t -> live.none { it.tariffRateId == t.tariffRateId } }
        val merged = (alive + live)
            .distinctBy { it.tariffRateId }
            .sortedByDescending { it.version ?: 0L }
        prefs.edit { it[KEY_TARIFFS_JSON] = tariffsAdapter.toJson(merged) }
    }

    suspend fun mergeFiscalizers(rows: List<FiscalizerRow>) {
        val live = rows.filter { it.deletedAt == null }
        val alive = fiscalizers().filter { f -> live.none { it.fiscalizerId == f.fiscalizerId } }
        val merged = (alive + live)
            .distinctBy { it.fiscalizerId }
            .sortedByDescending { it.version ?: 0L }
        prefs.edit { it[KEY_FISCALIZERS_JSON] = fiscalizersAdapter.toJson(merged) }
    }

    suspend fun mergeDistributorFiscalizers(rows: List<DistributorFiscalizerRow>) {
        val live = rows.filter { it.deletedAt == null }
        val alive = distributorFiscalizers().filter { d -> live.none { it.distributorFiscalizerId == d.distributorFiscalizerId } }
        val merged = (alive + live)
            .distinctBy { it.distributorFiscalizerId }
            .sortedByDescending { it.version ?: 0L }
        prefs.edit { it[KEY_DISTR_FISCALIZERS_JSON] = distributorFiscalizersAdapter.toJson(merged) }
    }

    suspend fun markSyncedAt(ms: Long) = prefs.edit { it[KEY_SYNCED_AT] = ms }

    suspend fun lastSyncedAtMs(): Long = prefs.data.first()[KEY_SYNCED_AT] ?: 0L

    private fun decodeKeys(json: String?): List<AsopKeyRow> =
        if (json.isNullOrBlank()) emptyList() else runCatching { keysAdapter.fromJson(json) }.getOrNull() ?: emptyList()

    private fun decodeTariffs(json: String?): List<TariffRateRow> =
        if (json.isNullOrBlank()) emptyList() else runCatching { tariffsAdapter.fromJson(json) }.getOrNull() ?: emptyList()

    private fun decodeFiscalizers(json: String?): List<FiscalizerRow> =
        if (json.isNullOrBlank()) emptyList() else runCatching { fiscalizersAdapter.fromJson(json) }.getOrNull() ?: emptyList()

    private fun decodeDistributorFiscalizers(json: String?): List<DistributorFiscalizerRow> =
        if (json.isNullOrBlank()) emptyList() else runCatching { distributorFiscalizersAdapter.fromJson(json) }.getOrNull() ?: emptyList()
}