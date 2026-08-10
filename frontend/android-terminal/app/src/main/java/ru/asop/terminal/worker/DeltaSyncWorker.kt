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
 * Раз в час (или вручную): шлёт delta-запрос (202 + eventId) на gateway,
 * сохраняет задание в Room. Скачивание чанков делает DeltaChunkPollWorker.
 *
 * Для периодического запуска (КЕЕП, без флага forced) уважает
 * deltaJobsEnabled. Принудительный (forced, one-shot, «Дельта сейчас»)
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
    private val workScheduler: WorkScheduler
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
        val terminalId: String? = if (forced) {
            inputData.getString(KEY_TERMINAL_ID)
        } else {
            syncPreferences.terminalId.first()
        }
        if (terminalId.isNullOrBlank()) return Result.failure()
        val tid: String = terminalId

        if (!forced && !syncPreferences.deltaJobsEnabled.first()) return Result.success()

        // Одно in-flight задание за раз
        if (deltaSyncJobDao.getPending().isNotEmpty()) return Result.success()

        try {
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
                return Result.retry()
            }
            val eventId = response.body()?.eventId ?: return Result.retry()
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
            return Result.retry()
        }
    }
}
