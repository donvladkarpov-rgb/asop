package ru.asop.terminal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ru.asop.terminal.db.ReferenceSyncStore
import ru.asop.terminal.db.dao.DeltaSyncJobDao
import ru.asop.terminal.network.GatewayApi
import ru.asop.proto.v1.DeltaChunk

/**
 * Поллит COMPLETED для PENDING delta-заданий, скачивает чанки из Redis
 * (через gateway) и атомарно накатывает в Room. При любой ошибке —
 * забывает eventId (через час DeltaSyncWorker перезапросит).
 */
@HiltWorker
class DeltaChunkPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val deltaSyncJobDao: DeltaSyncJobDao,
    private val referenceSyncStore: ReferenceSyncStore,
    private val gatewayApi: GatewayApi
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DeltaChunkPollWorker"
        private const val JOB_TTL_MS = 24L * 60 * 60 * 1000
    }

    override suspend fun doWork(): Result {
        val pending = deltaSyncJobDao.getPending()
        if (pending.isEmpty()) return Result.success()

        for (job in pending) {
            val eventId = job.eventId
            if (System.currentTimeMillis() - job.requestedAt > JOB_TTL_MS) {
                deltaSyncJobDao.delete(eventId)
                Log.w(TAG, "Delta job expired: $eventId")
                continue
            }
            try {
                if (!process(job.eventId)) {
                    // событие ещё в полёте / не готово — оставляем, дождёмся следующего цикла
                    continue
                }
            } catch (e: Exception) {
                // Локальная ошибка (чанк не пришёл, протокол) — забываем eventId целиком
                Log.w(TAG, "Forgetting delta event $eventId: ${e.message}")
                deltaSyncJobDao.delete(eventId)
            }
        }
        return Result.success()
    }

    /** @return true если событие обработано до конца (или забыто), false если ещё PENDING. */
    private suspend fun process(eventId: String): Boolean {
        val status = gatewayApi.getEventStatus(eventId)

        when (status.code()) {
            404 -> {
                deltaSyncJobDao.delete(eventId)
                return true
            }
            422 -> {
                deltaSyncJobDao.markFailed(eventId, "Delta failed (422)", System.currentTimeMillis())
                return true
            }
        }
        if (!status.isSuccessful || status.body() == null) return false

        val body = status.body()!!
        return when (body.state) {
            "COMPLETED" -> {
                val meta = gatewayApi.getDeltaMeta(eventId)
                if (!meta.isSuccessful || meta.body() == null) {
                    throw IllegalStateException("Delta meta missing for $eventId")
                }
                val totalChunks = meta.body()!!.totalChunks ?: 0
                if (totalChunks <= 0) {
                    deltaSyncJobDao.markCompleted(eventId, 0, System.currentTimeMillis())
                    return true
                }
                for (n in 0 until totalChunks) {
                    val chunkResponse = gatewayApi.getDeltaChunk(eventId, n)
                    if (!chunkResponse.isSuccessful || chunkResponse.body() == null) {
                        throw IllegalStateException("Chunk $n missing for $eventId")
                    }
                    val bytes = chunkResponse.body()!!.bytes()
                    val chunk = DeltaChunk.parseFrom(bytes)
                    referenceSyncStore.applyChunk(chunk)
                }
                deltaSyncJobDao.markCompleted(eventId, totalChunks, System.currentTimeMillis())
                Log.d(TAG, "Delta applied: $eventId, chunks=$totalChunks")
                true
            }
            "FAILED" -> {
                deltaSyncJobDao.markFailed(
                    eventId,
                    body.errorMessage ?: "Delta failed",
                    System.currentTimeMillis()
                )
                true
            }
            else -> false // PENDING — подождём
        }
    }
}
