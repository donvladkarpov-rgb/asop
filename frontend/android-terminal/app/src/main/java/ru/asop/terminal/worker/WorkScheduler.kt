package ru.asop.terminal.worker

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val SYNC_WORK_NAME = "sync_pending_events"
        private const val POLL_WORK_NAME = "poll_pending_events"
        private const val DELTA_WORK_NAME = "delta_sync"
        private const val DELTA_POLL_WORK_NAME = "delta_chunk_poll"
        private const val FULL_DUMP_WORK_NAME = "full_dump_download"
    }

    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        val pollRequest = PeriodicWorkRequestBuilder<EventPollWorker>(
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        val deltaRequest = PeriodicWorkRequestBuilder<DeltaSyncWorker>(
            60, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        val deltaPollRequest = PeriodicWorkRequestBuilder<DeltaChunkPollWorker>(
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            POLL_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            pollRequest
        )

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DELTA_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            deltaRequest
        )

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DELTA_POLL_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            deltaPollRequest
        )
    }

    /** Отменяет периодические delta-джобы. Текущее in-flight задание завершится, новые не начнутся. */
    fun stopDeltaJobs() {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DELTA_WORK_NAME)
        wm.cancelUniqueWork(DELTA_POLL_WORK_NAME)
    }

    /** Перезапускает периодические delta-джобы после stopDeltaJobs(). */
    fun startDeltaJobs() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val deltaRequest = PeriodicWorkRequestBuilder<DeltaSyncWorker>(
            60, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()

        val deltaPollRequest = PeriodicWorkRequestBuilder<DeltaChunkPollWorker>(
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            DELTA_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            deltaRequest
        )
        wm.enqueueUniquePeriodicWork(
            DELTA_POLL_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            deltaPollRequest
        )
    }

    fun enqueueOneShotSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }

    /** Ручной delta-запрос (debug/fallback). Идёт всегда, независимо от включения периодических джоб. */
    fun enqueueOneShotDelta(
        terminalId: String? = null,
        carrierId: String? = null,
        regionId: String? = null,
        lastVersion: Long? = null
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = DeltaSyncWorker.buildForcedData(
            terminalId = terminalId ?: "",
            carrierId = carrierId,
            regionId = regionId,
            lastVersion = lastVersion
        )

        val request = OneTimeWorkRequestBuilder<DeltaSyncWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }

    /** Принудительный чанк-поллить (после «Дельта сейчас»), независим от deltaJobsEnabled. */
    fun enqueueForcedDeltaChunkPoll() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DeltaChunkPollWorker>()
            .setConstraints(constraints)
            .setInputData(DeltaChunkPollWorker.buildForcedData())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "forced_delta_chunk_poll",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Полная выкачка: запрос + download worker. */
    fun enqueueFullDump(eventId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<FullDumpDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(FullDumpDownloadWorker.buildData(eventId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            FULL_DUMP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
