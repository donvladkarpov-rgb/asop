package ru.asop.terminal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
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

/**
 * Раз в час: шлёт delta-запрос (202 + eventId) на gateway, сохраняет
 * задание в Room. Скачивание чанков делает DeltaChunkPollWorker.
 */
@HiltWorker
class DeltaSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncPreferences: SyncPreferences,
    private val syncMetaDao: SyncMetaDao,
    private val deltaSyncJobDao: DeltaSyncJobDao,
    private val gatewayApi: GatewayApi
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DeltaSyncWorker"
    }

    override suspend fun doWork(): Result {
        val terminalId = syncPreferences.terminalId.first() ?: return Result.success()
        if (terminalId.isBlank()) return Result.success()

        // Одно in-flight задание за раз
        if (deltaSyncJobDao.getPending().isNotEmpty()) return Result.success()

        try {
            val lastVersion = syncMetaDao.get()?.lastVersion
            val carrierId = syncPreferences.carrierId.first()
            val regionId = syncPreferences.regionId.first()
            val response = gatewayApi.deltaSync(
                DeltaSyncRequest(terminalId, carrierId, regionId, lastVersion)
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
            Log.d(TAG, "Delta requested: eventId=$eventId, lastVersion=$lastVersion")
            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Delta request failed: ${e.message}")
            return Result.retry()
        }
    }
}
