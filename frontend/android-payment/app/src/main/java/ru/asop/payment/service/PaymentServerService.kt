package ru.asop.payment.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ru.asop.payment.PaymentApp
import ru.asop.payment.core.LocalPaymentServer

/**
 * Foreground-сервис, удерживающий локальный HTTP-сервер app-payment живым.
 *
 * Android 12+ (App Freezer) замораживает фоновые процессы (cgroup.freeze=1):
 * accept()-поток перестаёт работать → дистрибьютор получает timeout на
 * POST /pay («Нет связи с app-payment»). FGS поднимает приоритет процесса
 * и исключает его из freezer.
 *
 * Тип specialUse (не dataSync): dataSync на targetSdk 35 ограничен 6 часами
 * в сутки, локальный сервер должен работать все время смены.
 */
class PaymentServerService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        server().start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun server(): LocalPaymentServer =
        (application as PaymentApp).paymentServer

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Локальный сервер платежей",
                NotificationManager.IMPORTANCE_MIN
            )
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("app-payment")
            .setContentText("Локальный сервер 127.0.0.1:${LocalPaymentServer.PORT} работает")
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "payment_server"
        private const val NOTIFICATION_ID = 2001

        /** Безопасный запуск: на Android 12+ из background FGS может бросить исключение. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, PaymentServerService::class.java))
            } catch (e: Exception) {
                // FGS запрещён из background — сервер стартует напрямую (до freeze).
                android.util.Log.w("PaymentServerService", "FGS start failed, direct start", e)
                LocalPaymentServer.get(context).start()
            }
        }
    }
}
