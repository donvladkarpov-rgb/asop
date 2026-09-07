package ru.asop.terminal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import retrofit2.Response
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.network.SyncApi
import ru.asop.terminal.network.models.TerminalEventWatermark

/**
 * Промпт 012: WatermarkSyncWorker — periodic (1 час) sync per-terminal lastSeq с сервера.
 *
 * Делает GET /api/v1/terminals/{id}/event-watermark чтобы получить:
 *   - server.lastSeq
 *   - server.pendingSeqCount
 * Затем syncPreferences.setServerWatermark(serverLastSeq) — nextSeq() в
 * SyncPreferences будет монотонно следующий после watermark, гарантируя
 * что терминал никогда не переиспользует уже-применённый сервером seq.
 *
 * Run conditions:
 *   - terminalId has been registered (saved in SyncPreferences)
 *   - self-rescheduling цепочка (интервал из профиля) + старт при старте приложения.
 */
@HiltWorker
class WatermarkSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncApi: SyncApi,
    private val syncPreferences: SyncPreferences,
    private val workScheduler: WorkScheduler,
    private val terminalProfileProvider: TerminalProfileProvider
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "WatermarkSyncWorker"
        const val WORK_NAME = "watermark_sync"
    }

    /**
     * Промпт 019: self-rescheduling one-shot (интервал из профиля).
     * Возвращает success и переустанавливает следующий цикл — Result.retry()
     * не используется (нельзя задваивать цепочку).
     */
    override suspend fun doWork(): Result {
        val params = terminalProfileProvider.params()
        return try {
            val terminalId = syncPreferences.terminalId.first()
            if (terminalId.isNullOrBlank()) {
                Log.d(TAG, "No terminalId registered, skipping watermark sync")
                return Result.success()
            }

            val response: Response<TerminalEventWatermark> = syncApi.getEventWatermark(terminalId)
            if (!response.isSuccessful) {
                Log.w(TAG, "Watermark GET HTTP ${response.code()} for terminalId=$terminalId")
                return Result.success()
            }
            val wm = response.body()
            if (wm == null) {
                Log.w(TAG, "Watermark body null for terminalId=$terminalId")
                return Result.success()
            }
            syncPreferences.setServerWatermark(wm.lastSeq)
            Log.i(TAG, "Watermark synced: serverLastSeq=${wm.lastSeq} pending=${wm.pendingSeqCount} terminalId=$terminalId")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Watermark sync failed: ${e.message}, will retry next cycle")
            Result.success()
        } finally {
            workScheduler.rescheduleWatermark(params.watermarkIntervalMs)
        }
    }
}
