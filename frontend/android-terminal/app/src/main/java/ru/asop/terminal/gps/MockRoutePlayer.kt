package ru.asop.terminal.gps

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Промпт 015: Mock route player for debug GPS.
 * Reads a pre-recorded route from assets and emits coordinates at fixed intervals,
 * replacing FusedLocationProviderClient when isDebugGps is enabled.
 */
class MockRoutePlayer(
    private val context: Context,
    private val moshi: Moshi,
    private val onLocationUpdate: (lat: Double, lon: Double, speedKmh: Double?) -> Unit,
) {

    companion object {
        private const val TAG = "MockRoutePlayer"
        private const val ROUTE_FILE = "mock_route_301.json"
    }

    private data class RoutePoint(
        val lat: Double,
        val lon: Double,
        val speed: Double?,
        val delayMs: Long,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var points: List<RoutePoint> = emptyList()
    private var currentIndex = 0
    private var running = false

    private val runnable = object : Runnable {
        override fun run() {
            if (!running || points.isEmpty()) return
            val point = points[currentIndex]
            Log.d(TAG, "Mock GPS point ${currentIndex + 1}/${points.size}: ${point.lat}, ${point.lon}")
            onLocationUpdate(point.lat, point.lon, point.speed)
            currentIndex = (currentIndex + 1) % points.size
            handler.postDelayed(this, point.delayMs)
        }
    }

    fun start() {
        if (running) return
        if (points.isEmpty()) {
            loadRoute()
        }
        if (points.isEmpty()) {
            Log.e(TAG, "No mock route points loaded")
            return
        }
        running = true
        currentIndex = 0
        Log.d(TAG, "Mock route player started with ${points.size} points")
        handler.post(runnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
        Log.d(TAG, "Mock route player stopped")
    }

    private fun loadRoute() {
        try {
            val json = context.assets.open(ROUTE_FILE).bufferedReader().use { it.readText() }
            val type = Types.newParameterizedType(List::class.java, RoutePoint::class.java)
            val adapter = moshi.adapter<List<RoutePoint>>(type)
            points = adapter.fromJson(json) ?: emptyList()
            Log.d(TAG, "Loaded ${points.size} mock route points from $ROUTE_FILE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load mock route", e)
            points = emptyList()
        }
    }
}
