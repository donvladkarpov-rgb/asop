package ru.asop.terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.squareup.moshi.Moshi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.asop.terminal.MainActivity
import ru.asop.terminal.db.dao.PendingEventDao
import ru.asop.terminal.db.dao.SessionDao
import ru.asop.terminal.db.entity.PendingEventEntity
import ru.asop.terminal.db.SyncPreferences
import ru.asop.terminal.gps.MockRoutePlayer
import ru.asop.terminal.network.SyncApi
import ru.asop.terminal.network.SeqHeaderHolder
import ru.asop.terminal.network.models.GpsPositionReport
import ru.asop.terminal.worker.EventTypes
import ru.asop.terminal.worker.WorkScheduler
import java.time.Instant
import javax.inject.Inject

@AndroidEntryPoint
class GpsTrackingService : android.app.Service() {

    @Inject lateinit var fusedLocationClient: FusedLocationProviderClient
    @Inject lateinit var syncApi: SyncApi
    @Inject lateinit var pendingEventDao: PendingEventDao
    @Inject lateinit var sessionDao: SessionDao
    @Inject lateinit var syncPreferences: SyncPreferences
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var moshi: Moshi

    private var mockRoutePlayer: MockRoutePlayer? = null

    companion object {
        private const val TAG = "GpsTrackingService"
        private const val CHANNEL_ID = "gps_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MS = 5_000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 3_000L
        private const val GPS_BATCH_SIZE = 10
        const val ACTION_STOP = "ru.asop.terminal.action.STOP_GPS"

        fun start(context: Context) {
            val intent = Intent(context, GpsTrackingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GpsTrackingService::class.java))
        }
    }

    private var pointCount = 0
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            serviceScope.launch {
                // Промпт 011: GPS привязывается к SHIFT (parent root session), не к TRIP,
                // чтобы отчёты оставались согласованными между открытыми/закрытыми рейсами в одной смене.
                val shift = sessionDao.getCurrentOpenShift()
                val trip = shift?.let { sessionDao.getCurrentOpenTrip(it.id) }
                val sessionId = shift?.id
                // VCM1/DESFire: ASOP_GPS_TRACKING.VEHICLE_ID/PATH_ID NOT NULL на сервере.
                // Раньше здесь было `trip?.vehicleId ?: ""` (@NotNull UUID) → 400 при отсутствии
                // рейса. Теперь берём vehicleId/pathId из рейса, fallback на смену; если of них
                // нет — пропускаем точку (GPS без привязки к маршруту невалиден).
                val vehicleId = trip?.vehicleId ?: shift?.vehicleId
                val pathId = trip?.pathId ?: shift?.pathId
                if (vehicleId == null || pathId == null) {
                    return@launch
                }
                val report = GpsPositionReport(
                    vehicleId = vehicleId,
                    pathId = pathId,
                    sessionId = sessionId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedKmh = if (location.hasSpeed()) location.speed * 3.6 else null,
                    recordedAt = java.time.Instant.now().toString()
                )
                enqueueGpsReport(report)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startLocationUpdates()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        stopLocationUpdates()
        super.onDestroy()
    }

    @Volatile
    private var locationUpdatesStarted = false

    private fun startLocationUpdates() {
        if (locationUpdatesStarted) return
        locationUpdatesStarted = true
        serviceScope.launch {
            val debugGps = syncPreferences.isDebugGps.first()
            if (debugGps) {
                Log.d(TAG, "Debug GPS mode: using MockRoutePlayer")
                mockRoutePlayer = MockRoutePlayer(applicationContext, moshi) { lat, lon, speed ->
                    serviceScope.launch {
                        handleMockLocation(lat, lon, speed)
                    }
                }
                mockRoutePlayer?.start()
            } else {
                try {
                    val request = LocationRequest.Builder(LOCATION_INTERVAL_MS)
                        .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
                        .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                        .build()
                    fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
                    Log.d(TAG, "Location updates started (FusedLocationProvider)")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing location permission", e)
                    stopSelf()
                }
            }
        }
    }

    private fun stopLocationUpdates() {
        locationUpdatesStarted = false
        mockRoutePlayer?.stop()
        mockRoutePlayer = null
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Location updates stopped")
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun handleMockLocation(lat: Double, lon: Double, speedKmh: Double?) {
        val shift = sessionDao.getCurrentOpenShift()
        val trip = shift?.let { sessionDao.getCurrentOpenTrip(it.id) }
        val sessionId = shift?.id
        val vehicleId = trip?.vehicleId ?: shift?.vehicleId
        val pathId = trip?.pathId ?: shift?.pathId
        if (vehicleId == null || pathId == null) {
            return
        }
        val report = GpsPositionReport(
            vehicleId = vehicleId,
            pathId = pathId,
            sessionId = sessionId,
            latitude = lat,
            longitude = lon,
            speedKmh = speedKmh,
            recordedAt = Instant.now().toString()
        )
        enqueueGpsReport(report)
    }

    private fun enqueueGpsReport(report: GpsPositionReport) {
        val adapter = moshi.adapter(GpsPositionReport::class.java)
        val payload = adapter.toJson(report)
        val event = PendingEventEntity(
            topic = "asop.gps.commands",
            payload = payload,
            eventType = EventTypes.GPS_POSITION,
            seq = syncPreferences.nextSeqSync()
        )
        serviceScope.launch {
            try {
                SeqHeaderHolder.setSeq(event.seq)
                val response = syncApi.reportGpsPosition(report)
                SeqHeaderHolder.clear()
                if (response.isSuccessful) {
                    Log.d(TAG, "GPS position sent online")
                    return@launch
                }
            } catch (_: Exception) { }
            pendingEventDao.insert(event)
            pointCount++
            Log.d(TAG, "GPS queued offline ($pointCount/$GPS_BATCH_SIZE)")
            if (pointCount >= GPS_BATCH_SIZE) {
                pointCount = 0
                workScheduler.enqueueOneShotSync()
                Log.d(TAG, "GPS batch threshold reached, triggering sync")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GPS Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Уведомление о работе геопозиции"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ASOP Терминал")
            .setContentText("Геопозиция активна")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

}
