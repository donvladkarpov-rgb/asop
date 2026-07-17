package ru.asop.terminal.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.squareup.moshi.Moshi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.Response
import ru.asop.terminal.db.dao.PendingEventDao
import ru.asop.terminal.db.dao.SessionDao
import ru.asop.terminal.db.entity.PendingEventEntity
import ru.asop.terminal.network.SyncApi
import ru.asop.terminal.network.models.*

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingEventDao: PendingEventDao,
    private val sessionDao: SessionDao,
    private val syncApi: SyncApi,
    private val moshi: Moshi
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRIES = 5
    }

    override suspend fun doWork(): Result {
        val pending = pendingEventDao.getPending()
        if (pending.isEmpty()) return Result.success()

        for (event in pending) {
            if (event.retryCount >= MAX_RETRIES) {
                pendingEventDao.markFailed(event.id, "Max retries ($MAX_RETRIES) exceeded")
                Log.w(TAG, "Max retries for ${event.eventType}: ${event.id}")
                continue
            }
            try {
                val gatewayEventId = sendEvent(event)
                pendingEventDao.markSending(event.id, gatewayEventId)
                Log.d(TAG, "Sent ${event.eventType} -> eventId=$gatewayEventId")
            } catch (e: Exception) {
                pendingEventDao.incrementPollRetry(event.id)
                Log.w(TAG, "Transient error sending ${event.eventType}: ${e.message}, will retry")
                return Result.retry()
            }
        }
        return Result.success()
    }

    private suspend fun sendEvent(event: PendingEventEntity): String {
        val payload = event.payload
        val pathId = event.pathParam
        return when (event.eventType) {
            EventTypes.SESSION_OPEN -> {
                syncApi.openSession(deserialize(payload)).toEventId()
            }
            EventTypes.SESSION_CLOSE -> {
                val req = if (payload.isNotEmpty()) deserialize<SessionCloseRequest>(payload)
                    else SessionCloseRequest()
                syncApi.closeSession(pathId ?: "", req).toEventId()
            }
            EventTypes.TRANSACTION_COMPLETE -> {
                syncApi.completeTransaction(deserialize(payload)).toEventId()
            }
            EventTypes.CARD_REGISTER -> {
                syncApi.registerCard(deserialize(payload)).toEventId()
            }
            EventTypes.CARD_BLOCK -> {
                val req = if (payload.isNotEmpty()) deserialize<CardBlockRequest>(payload)
                    else CardBlockRequest(blockType = "TERMINAL")
                syncApi.blockCard(pathId ?: "", req).toEventId()
            }
            EventTypes.DEBT_CREATE -> {
                syncApi.createDebt(deserialize(payload)).toEventId()
            }
            EventTypes.DEBT_RECOVER -> {
                syncApi.recoverDebt(pathId ?: "").toEventId()
            }
            EventTypes.FISCAL_RECEIPT -> {
                syncApi.requestFiscalReceipt(deserialize(payload)).toEventId()
            }
            EventTypes.AUDIT_TASK -> {
                syncApi.createAuditTask(deserialize(payload)).toEventId()
            }
            EventTypes.GPS_POSITION -> {
                syncApi.reportGpsPosition(deserialize(payload)).toEventId()
            }
            else -> throw IllegalArgumentException("Unknown event type: ${event.eventType}")
        }
    }

    private inline fun <reified T> deserialize(json: String): T {
        val adapter = moshi.adapter(T::class.java)
        return adapter.fromJson(json) ?: throw IllegalArgumentException("Failed to deserialize $json")
    }

    private fun Response<AcceptedResponse>.toEventId(): String {
        if (!isSuccessful) {
            throw Exception("HTTP ${code()}: ${message()}")
        }
        return body()?.eventId ?: throw Exception("Empty body")
    }
}
