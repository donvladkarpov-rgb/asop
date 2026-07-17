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
                sendEvent(event).onSuccess { gatewayEventId ->
                    pendingEventDao.markSending(event.id, gatewayEventId)
                    Log.d(TAG, "Sent ${event.eventType} -> eventId=$gatewayEventId")
                }.onFailure { error ->
                    pendingEventDao.markFailed(event.id, error.message ?: "Unknown error")
                    Log.w(TAG, "Failed ${event.eventType}: ${error.message}")
                }
            } catch (e: Exception) {
                pendingEventDao.markFailed(event.id, e.message ?: "Unknown error")
                Log.e(TAG, "Error sending ${event.eventType}", e)
                return Result.retry()
            }
        }
        return Result.success()
    }

    private suspend fun sendEvent(event: PendingEventEntity): Result<String> {
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
                // Backend PUT /debts/{id}/recover takes NO request body
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
            else -> Result.failure(IllegalArgumentException("Unknown event type: ${event.eventType}"))
        }
    }

    private inline fun <reified T> deserialize(json: String): T {
        val adapter = moshi.adapter(T::class.java)
        return adapter.fromJson(json) ?: throw IllegalArgumentException("Failed to deserialize $json")
    }

    private fun Response<AcceptedResponse>.toEventId(): Result<String> {
        if (!isSuccessful) {
            return Result.failure(Exception("HTTP ${code()}: ${message()}"))
        }
        val body = body() ?: return Result.failure(Exception("Empty body"))
        return Result.success(body.eventId)
    }

    class Result<T>(val value: T?) {
        private val error: Throwable?

        private constructor(value: T) : this(value, null)
        private constructor(error: Throwable) : this(null, error)

        companion object {
            fun <T> success(value: T): Result<T> = Result(value)
            fun <T> failure(error: Throwable): Result<T> = Result(error)
        }

        fun onSuccess(action: (T) -> Unit): Result<T> {
            if (error == null) action(value!!)
            return this
        }

        fun onFailure(action: (Throwable) -> Unit): Result<T> {
            if (error != null) action(error)
            return this
        }
    }
}
