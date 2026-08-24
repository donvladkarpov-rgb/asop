package ru.asop.terminal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import ru.asop.terminal.db.DeltaProgressTracker
import ru.asop.terminal.db.ReferenceSyncStore
import ru.asop.terminal.db.dao.DeltaSyncJobDao
import ru.asop.terminal.db.entity.DeltaSyncJobEntity
import ru.asop.terminal.network.GatewayApi
import java.util.zip.ZipInputStream

/**
 * One-shot полная выкачка: поллит event до COMPLETED, качает ZIP
 * (full_{eventId}.zip) через gateway-proxy MinIO, распаковывает .pb-файлы
 * и атомарно накатывает справочники в Room.
 *
 * Пользовательский one-shot: выполняется независимо от флага deltaJobsEnabled.
 */
@HiltWorker
class FullDumpDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val deltaSyncJobDao: DeltaSyncJobDao,
    private val referenceSyncStore: ReferenceSyncStore,
    private val gatewayApi: GatewayApi,
    private val deltaProgressTracker: DeltaProgressTracker
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "FullDumpDownloadWorker"
        private const val MAX_POLL_ATTEMPTS = 60
        private const val POLL_INTERVAL_MS = 10_000L

        fun buildData(eventId: String) = androidx.work.Data.Builder()
            .putString("eventId", eventId)
            .build()
    }

    override suspend fun doWork(): Result {
        val eventId = inputData.getString("eventId") ?: return Result.failure()
        try {
            // Если уже завершён ранее (retry после частичного успеха) — событие исчезло
            if (gatewayApi.getEventStatus(eventId).code() == 404) {
                deltaSyncJobDao.markCompleted(eventId, 0, System.currentTimeMillis())
                return Result.success()
            }

            val completed = waitForCompleted(eventId)
            if (!completed) return Result.retry()

            val response = gatewayApi.downloadFullDump(eventId)
            if (!response.isSuccessful || response.body() == null) {
                Log.w(TAG, "Full dump download failed: HTTP ${response.code()}")
                return Result.retry()
            }
            val bytes = response.body()!!.bytes()

            // Считаем .pb-файлы в ZIP для прогресса
            var pbCount = 0
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".pb")) pbCount++
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            deltaProgressTracker.startDelta(eventId, pbCount)

            // Все .pb-файлы собираются и применяются АТОМАРНО (clear + apply + watermark)
            // — полная выкачка заменяет справочники целиком, мёртвые строки прошлых
            // выкачок/другой БД не остаются (ReferenceSyncStore.applyFullDump).
            val files = mutableMapOf<String, ByteArray>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".pb")) {
                        val table = "asop_${entry.name.removeSuffix(".pb")}"
                        files[table] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            referenceSyncStore.applyFullDump(files)
            deltaProgressTracker.finish(eventId)

            deltaSyncJobDao.markCompleted(eventId, 0, System.currentTimeMillis())
            Log.d(TAG, "Full dump applied: $eventId")
            return Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Full dump failed: ${e.message}")
            deltaSyncJobDao.delete(eventId)
            return Result.retry()
        }
    }

    private suspend fun waitForCompleted(eventId: String): Boolean {
        repeat(MAX_POLL_ATTEMPTS) {
            val status = gatewayApi.getEventStatus(eventId)
            if (status.code() == 422) return false
            if (status.isSuccessful) {
                val body = status.body()
                if (body?.state == "COMPLETED") return true
                if (body?.state == "FAILED") return false
            }
            delay(POLL_INTERVAL_MS)
        }
        return false
    }
}
