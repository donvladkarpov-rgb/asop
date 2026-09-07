package ru.asop.terminal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.db.dao.DeltaSyncJobDao
import ru.asop.terminal.db.dao.SyncMetaDao
import ru.asop.terminal.db.entity.DeltaSyncJobEntity
import ru.asop.terminal.network.GatewayApi
import ru.asop.terminal.network.models.DeltaSyncRequest
import ru.asop.terminal.worker.WorkScheduler

/**
 * Промпт 019: self-rescheduling one-shot цепочка (интервал deltaSyncIntervalMs из профиля)
 * или ручной (forced) запрос: шлёт delta-запрос (202 + eventId) на gateway,
 * сохраняет задание в Room. Скачивание чанков делает DeltaChunkPollWorker.
 *
 * Non-forced цепочка уважает deltaJobsEnabled (при выключении — не переустанавливает
 * себя, цепочка умирает). Принудительный (forced, one-shot, «Дельта сейчас»)
 * работает всегда, независимо от флага.
 */
@HiltWorker
class DeltaSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncPreferences: SyncPreferences,
    private val syncMetaDao: SyncMetaDao,
    private val deltaSyncJobDao: DeltaSyncJobDao,
    private val gatewayApi: GatewayApi,
    private val workScheduler: WorkScheduler,
    private val terminalProfileProvider: TerminalProfileProvider
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DeltaSyncWorker"

        const val KEY_FORCED = "forced"
        const val KEY_TERMINAL_ID = "terminal_id"
        const val KEY_CARRIER_ID = "carrier_id"
        const val KEY_REGION_ID = "region_id"
        const val KEY_LAST_VERSION = "last_version"

        fun buildForcedData(
            terminalId: String,
            carrierId: String?,
            regionId: String?,
            lastVersion: Long?
        ): Data = Data.Builder()
            .putBoolean(KEY_FORCED, true)
            .putString(KEY_TERMINAL_ID, terminalId)
            .putString(KEY_CARRIER_ID, carrierId)
            .putString(KEY_REGION_ID, regionId)
            .putLong(KEY_LAST_VERSION, lastVersion ?: 0L)
            .build()
    }

    override suspend fun doWork(): Result {
        val forced = inputData.getBoolean(KEY_FORCED, false)
        val params = terminalProfileProvider.params()

        // Non-forced цепочка честно умирает при выключенных автоджобах
        // (иначе заново переenqueue'ится и stopDeltaJobs не сработает).
        if (!forced && !syncPreferences.deltaJobsEnabled.first()) return Result.success()

        try {
            val terminalId: String? = if (forced) {
                inputData.getString(KEY_TERMINAL_ID)
            } else {
                syncPreferences.terminalId.first()
            }
            if (terminalId.isNullOrBlank()) return Result.success()
            val tid: String = terminalId

            // Одно in-flight задание за раз
            if (deltaSyncJobDao.getPending().isNotEmpty()) return Result.success()

            val lastVersion = if (forced) {
                val v = inputData.getLong(KEY_LAST_VERSION, 0L)
                if (v > 0L) v else null
            } else {
                syncMetaDao.get()?.lastVersion
            }
            val carrierId = if (forced) {
                inputData.getString(KEY_CARRIER_ID)
            } else {
                syncPreferences.carrierId.first()
            }
            val regionId = if (forced) {
                inputData.getString(KEY_REGION_ID)
            } else {
                syncPreferences.regionId.first()
            }

            val response = gatewayApi.deltaSync(
                DeltaSyncRequest(tid, carrierId, regionId, lastVersion)
            )

            if (!response.isSuccessful) {
                Log.w(TAG, "DeltaSync rejected: HTTP ${response.code()}")
                if (forced) return Result.retry()
                return Result.success()
            }
            val eventId = response.body()?.eventId
            if (eventId == null) {
                if (forced) return Result.retry()
                return Result.success()
            }
            deltaSyncJobDao.insert(
                DeltaSyncJobEntity(
                    eventId = eventId,
                    status = "PENDING",
                    requestedAt = System.currentTimeMillis()
                )
            )
            workScheduler.enqueueForcedDeltaChunkPoll()
            Log.d(TAG, "Delta requested: eventId=$eventId, lastVersion=$lastVersion, forced=$forced")
            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Delta request failed: ${e.message}, forced=$forced")
            if (forced) return Result.retry()
            return Result.success()
        } finally {
            // только non-forced цепочка переустанавливает себя каждые deltaSyncIntervalMs
            if (!forced) workScheduler.rescheduleDelta(params.deltaSyncIntervalMs)
        }
    }
}
