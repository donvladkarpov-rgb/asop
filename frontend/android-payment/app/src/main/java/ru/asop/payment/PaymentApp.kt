package ru.asop.payment

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ru.asop.payment.core.CardProbe
import ru.asop.payment.core.LocalPaymentServer
import ru.asop.payment.worker.ReportWorker
import java.util.concurrent.TimeUnit

class PaymentApp : Application() {

    /** Локальный HTTP-сервер живёт в процессе приложения (переживает закрытие Activity). */
    val paymentServer: LocalPaymentServer by lazy { LocalPaymentServer.get(this) }

    override fun onCreate() {
        super.onCreate()
        paymentServer.start()
        // Прогрев FTSDK ServiceManager при старте: первый handoff не ждёт bind (25 c).
        Thread({ CardProbe.get(this).checkNfc() }, "ftsdk-warmup").start()
        scheduleReportWorker(this)
    }

    /** Периодическая доставка отчётов о платежах (офлайн-очередь §4.3). */
    private fun scheduleReportWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<ReportWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "payment_report",
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }
}