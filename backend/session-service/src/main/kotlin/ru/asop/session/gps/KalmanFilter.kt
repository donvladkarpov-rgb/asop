package ru.asop.session.gps

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Simplified GPS position filter with exponential smoothing.
 * No Kalman P-collapse issues — uses a fixed gain (alpha) for stable, responsive tracking.
 * One instance per terminalId to maintain filter state across fixes.
 */
class GpsPositionFilter(
    /** Smoothing factor: 0 = ignore measurements, 1 = no smoothing (raw GPS). 0.6 = responsive with jitter reduction. */
    private val alpha: Double = 0.6
) {
    private var smoothedLat = 0.0
    private var smoothedLon = 0.0
    private var initialized = false
    private var lastAccessMs = System.currentTimeMillis()

    fun filter(lat: Double, lon: Double): Pair<Double, Double> {
        lastAccessMs = System.currentTimeMillis()

        if (!initialized) {
            smoothedLat = lat
            smoothedLon = lon
            initialized = true
            return lat to lon
        }

        smoothedLat += alpha * (lat - smoothedLat)
        smoothedLon += alpha * (lon - smoothedLon)

        return smoothedLat to smoothedLon
    }

    fun isExpired(idleMs: Long = 30 * 60 * 1000): Boolean =
        System.currentTimeMillis() - lastAccessMs > idleMs

    fun reset() { initialized = false }
}

@Component
class GpsPositionFilterRegistry {
    private val filters = ConcurrentHashMap<String, GpsPositionFilter>()

    fun getFilter(terminalId: String): GpsPositionFilter {
        return filters.computeIfAbsent(terminalId) { GpsPositionFilter() }
    }

    /** Cleanup expired filters (called by @Scheduled) */
    fun cleanup() {
        filters.entries.removeIf { it.value.isExpired() }
    }
}
