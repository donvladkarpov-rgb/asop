package ru.asop.terminal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ru.asop.terminal.db.dao.PendingEventDao
import ru.asop.terminal.network.GatewayApi

@HiltWorker
class EventPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingEventDao: PendingEventDao,
    private val gatewayApi: GatewayApi
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "EventPollWorker"
        private const val MAX_POLL_RETRIES = 20
    }

    override suspend fun doWork(): Result {
        val sending = pendingEventDao.getSending()
        if (sending.isEmpty()) return Result.success()

        for (event in sending) {
            val eventId = event.gatewayEventId ?: continue

            if (event.retryCount >= MAX_POLL_RETRIES) {
                pendingEventDao.markFailed(
                    event.id,
                    "Poll timeout after $MAX_POLL_RETRIES attempts"
                )
                Log.w(TAG, "Poll timeout for event $eventId")
                continue
            }

            try {
                val response = gatewayApi.getEventStatus(eventId)

                if (response.code() == 404) {
                    pendingEventDao.markFailed(
                        event.id,
                        "Event not found (gateway restarted, EventService TTL expired)"
                    )
                    Log.w(TAG, "Event $eventId not found on gateway")
                    continue
                }

                if (!response.isSuccessful) {
                    pendingEventDao.incrementPollRetry(event.id)
                    Log.w(TAG, "Poll failed for $eventId: HTTP ${response.code()}")
                    continue
                }

                val status = response.body() ?: continue
                when (status.state) {
                    "COMPLETED" -> {
                        pendingEventDao.markSent(event.id)
                        Log.d(TAG, "Event $eventId completed")
                    }
                    "FAILED" -> {
                        pendingEventDao.markFailed(
                            event.id,
                            status.errorMessage ?: "Event failed"
                        )
                        Log.w(TAG, "Event $eventId failed: ${status.errorMessage}")
                    }
                    "PENDING" -> {
                        pendingEventDao.incrementPollRetry(event.id)
                    }
                }
            } catch (e: Exception) {
                pendingEventDao.incrementPollRetry(event.id)
                Log.e(TAG, "Error polling event $eventId", e)
            }
        }
        return Result.success()
    }
}
