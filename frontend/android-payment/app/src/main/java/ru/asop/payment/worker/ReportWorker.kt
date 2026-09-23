package ru.asop.payment.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import ru.asop.payment.core.PayResponse
import ru.asop.payment.db.PaymentDatabase
import ru.asop.payment.network.AcquirerReportClient
import ru.asop.payment.network.ReportPayload

/**
 * Промпт 016 §4.3: фоновая доставка отчётов о платежах (`POST /api/v1/payment/report`).
 * Store-and-forward: прочитали PENDING из Room → собрали PaymentReportRequest → отправили
 * (когда pairing настроен) → пометили SENT. Не доставленные остаются PENDING (retry).
 */
class ReportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = PaymentDatabase.get(applicationContext).pendingPaymentDao()
        val client = AcquirerReportClient(applicationContext)
        val pending = dao.getPendingReports()
        if (pending.isEmpty()) return Result.success()

        var deferred = 0
        for (entity in pending) {
            val response = runCatching { JSONObject(entity.payloadJson) }
                .map { PayResponse.fromJson(it) }.getOrNull() ?: continue
            val payload = ReportPayload.build(response, entity.paymentType) ?: continue
            when (client.report(entity.paymentId, payload.toString())) {
                AcquirerReportClient.ReportResult.SENT -> {
                    dao.markReported(entity.paymentId, "SENT")
                    Log.i("ReportWorker", "reported ${entity.paymentId}")
                }
                AcquirerReportClient.ReportResult.FAILED -> { /* retry next run */ }
                AcquirerReportClient.ReportResult.DEFERRED -> deferred++
            }
        }
        return if (deferred > 0 || pending.any { it.reportStatus == "PENDING" })
            Result.retry() else Result.success()
    }
}