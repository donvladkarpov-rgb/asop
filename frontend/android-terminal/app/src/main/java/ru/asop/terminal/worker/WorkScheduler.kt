package ru.asop.terminal.worker

import android.content.Context
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Промпт 019: воркеры терминала — self-rescheduling one-shot циклы.
 *
 * PeriodicWorkRequest минимум 15 мин — не подходит для интервалов профиля (5–30 с).
 * Поэтому каждый воркер в конце doWork() ставит себе следующий OneTimeWorkRequest
 * с setInitialDelay(interval) через enqueueUniqueWork(name, REPLACE) и возвращает
 * Result.success() (без Result.retry() — иначе дублируются цепочки).
 * Интервалы из профиля терминала (ASOP_TERMINAL_PROFILES), фолбэк — TerminalProfileParams.DEFAULTS.
 *
 * При старте приложения у каждой цепочки ставится немедленный запуск (startWorkers),
 * плюс не-unique one-shot sync/poll для мгновенного ответа на сети.
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingEventPoller: PendingEventPoller
) {
    init { pendingEventPoller.start() }
    companion object {
        const val SYNC_WORK_NAME = "sync_pending_events"
        const val POLL_WORK_NAME = "poll_pending_events"
        const val DELTA_WORK_NAME = "delta_sync"
        const val DELTA_POLL_WORK_NAME = "delta_chunk_poll"
        const val FULL_DUMP_WORK_NAME = "full_dump_download"
        const val WATERMARK_WORK_NAME = "watermark_sync"
    }

    private fun connectedConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun syncRequest(delayMs: Long, inputData: Data = Data.EMPTY): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connectedConstraints())
            .setInputData(inputData)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

    private fun pollRequest(delayMs: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<EventPollWorker>()
            .setConstraints(connectedConstraints())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

    private fun deltaRequest(delayMs: Long, inputData: Data = Data.EMPTY): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DeltaSyncWorker>()
            .setConstraints(connectedConstraints())
            .setInputData(inputData)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

    private fun deltaPollRequest(delayMs: Long, inputData: Data = Data.EMPTY): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DeltaChunkPollWorker>()
            .setConstraints(connectedConstraints())
            .setInputData(inputData)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

    private fun watermarkRequest(delayMs: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<WatermarkSyncWorker>()
            .setConstraints(connectedConstraints())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

    /**
     * Старт всех цепочек с немедленными запусками (self-rescheduling).
     * REPLACE — гарантирует запуск прямо сейчас при каждом старте процесса
     * (заменяет отложенный next-run на мгновенный).
     */
    fun startWorkers() {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.REPLACE, syncRequest(0L))
        wm.enqueueUniqueWork(POLL_WORK_NAME, ExistingWorkPolicy.REPLACE, pollRequest(0L))
        wm.enqueueUniqueWork(WATERMARK_WORK_NAME, ExistingWorkPolicy.REPLACE, watermarkRequest(0L))
        enqueueDeltaChains()
        // Мгновенные не-unique one-shot — на случай старта с уже накопленным пендингом.
        enqueueOneShotSync()
        enqueueOneShotPoll()
    }

    private fun enqueueDeltaChains() {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(DELTA_WORK_NAME, ExistingWorkPolicy.REPLACE, deltaRequest(0L))
        wm.enqueueUniqueWork(DELTA_POLL_WORK_NAME, ExistingWorkPolicy.REPLACE, deltaPollRequest(0L))
    }

    // ---- self-rescheduling (вызываются самими воркерами в конце doWork) ----

    fun rescheduleSync(intervalMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_WORK_NAME, ExistingWorkPolicy.REPLACE, syncRequest(intervalMs)
        )
    }

    fun reschedulePoll(intervalMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            POLL_WORK_NAME, ExistingWorkPolicy.REPLACE, pollRequest(intervalMs)
        )
    }

    fun rescheduleDelta(intervalMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DELTA_WORK_NAME, ExistingWorkPolicy.REPLACE, deltaRequest(intervalMs)
        )
    }

    fun rescheduleDeltaPoll(intervalMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            DELTA_POLL_WORK_NAME, ExistingWorkPolicy.REPLACE, deltaPollRequest(intervalMs)
        )
    }

    fun rescheduleWatermark(intervalMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WATERMARK_WORK_NAME, ExistingWorkPolicy.REPLACE, watermarkRequest(intervalMs)
        )
    }

    /** Отменяет дельта-цепочки. In-flight задание завершится, новые не начнутся (воркер НЕ ре-шедулится при выключенном deltaJobsEnabled). */
    fun stopDeltaJobs() {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DELTA_WORK_NAME)
        wm.cancelUniqueWork(DELTA_POLL_WORK_NAME)
    }

    /** Перезапускает дельта-цепочки сразу (немедленный запуск) после stopDeltaJobs(). */
    fun startDeltaJobs() = enqueueDeltaChains()

    fun enqueueOneShotSync() = enqueueOneShotSync(0L)

    fun enqueueOneShotSync(delayMs: Long) {
        val request = syncRequest(delayMs)
        WorkManager.getInstance(context).enqueue(request)
    }

    fun enqueueOneShotPoll() {
        val request = pollRequest(0L)
        WorkManager.getInstance(context).enqueue(request)
    }

    /** Ручной delta-запрос (debug/fallback). Идёт всегда, независимо от включения периодических джоб. */
    fun enqueueOneShotDelta(
        terminalId: String? = null,
        carrierId: String? = null,
        regionId: String? = null,
        lastVersion: Long? = null
    ) {
        val data = DeltaSyncWorker.buildForcedData(
            terminalId = terminalId ?: "",
            carrierId = carrierId,
            regionId = regionId,
            lastVersion = lastVersion
        )

        val request = deltaRequest(0L, data)
        WorkManager.getInstance(context).enqueue(request)
    }

    /** Принудительный чанк-поллить (после «Дельта сейчас»), независим от deltaJobsEnabled. */
    fun enqueueForcedDeltaChunkPoll() {
        val request = OneTimeWorkRequestBuilder<DeltaChunkPollWorker>()
            .setConstraints(connectedConstraints())
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
        val request = OneTimeWorkRequestBuilder<FullDumpDownloadWorker>()
            .setConstraints(connectedConstraints())
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