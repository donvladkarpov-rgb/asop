package ru.asop.session.gps

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GpsRouteSnapper — привязка входящих GPS-координат к ближайшей точке маршрута (snap-to-route).
 *
 * Геометрия маршрута хранится в ASOP_PATHS.ROUTE_OBJECT (JSONB, GeoJSON LineString):
 *   { "type": "LineString", "coordinates": [[lon, lat], [lon, lat], ...] }
 *
 * Для каждой входящей точки находится ближайшая точка на ломаной маршрута
 * методом проекции на сегменты. Возвращает snapped координату.
 *
 * Загрузка геометрии — реактивная (Mono, без block() на reactor-потоках), с кешем в памяти.
 */
@Component
class GpsRouteSnapper(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
) {
    private val cache = ConcurrentHashMap<UUID, CachedGeometry>()
    private val loading = ConcurrentHashMap<UUID, Mono<CachedGeometry>>()
    private val log = LoggerFactory.getLogger(javaClass)

    private data class CachedGeometry(
        val points: List<DoubleArray>, // [lat, lon]
        val loadedAtMs: Long,
    )

    /** Инвалидировать кеш для конкретного пути (вызывается при изменении geometry). */
    fun invalidate(pathId: UUID) {
        cache.remove(pathId)
        loading.remove(pathId)
    }

    fun invalidateAll() {
        cache.clear()
        loading.clear()
    }

    /**
     * Возвращает snapped [SnappedPoint] для входящей точки, либо null если маршрут
     * неизвестен/не имеет геометрии или расстояние > maxDistanceMeters.
     * Реактивный — не блокирует reactor-потоки.
     */
    fun snap(pathId: UUID?, lat: Double, lon: Double, maxDistanceMeters: Double = 300.0): Mono<SnappedPoint?> {
        if (pathId == null) return Mono.empty()
        return getGeometry(pathId)
            .mapNotNull { geometry ->
                val snapped = snapToPoints(geometry.points, lat, lon, maxDistanceMeters)
                if (snapped != null) SnappedPoint(snapped[0], snapped[1]) else null
            }
    }

    private fun getGeometry(pathId: UUID): Mono<CachedGeometry> {
        val now = System.currentTimeMillis()
        val cached = cache[pathId]
        if (cached != null && now - cached.loadedAtMs < TTL_MS) {
            return Mono.just(cached)
        }

        // Re-entrancy guard: reuse an in-flight load for the same path
        return loading.computeIfAbsent(pathId) { pid ->
            loadGeometry(pid)
                .doOnNext { loaded ->
                    if (loaded.points.isNotEmpty()) {
                        cache[pid] = loaded
                    }
                }
                .doFinally { loading.remove(pid) }
                .onErrorResume { e ->
                    log.warn("Failed to load route geometry for path {}: {}", pid, e.message)
                    Mono.just(CachedGeometry(emptyList(), System.currentTimeMillis()))
                }
        }
    }

    private fun loadGeometry(pathId: UUID): Mono<CachedGeometry> {
        val sql = "SELECT ROUTE_OBJECT::text AS ROUTE_JSON FROM ASOP_PATHS WHERE PATH_ID = :pathId AND DELETED_AT IS NULL"
        return db.sql(sql)
            .bind("pathId", pathId)
            .map { row, _ ->
                val raw = row.get("ROUTE_JSON")
                when (raw) {
                    is ByteArray -> String(raw, Charsets.UTF_8)
                    else -> raw?.toString()
                }
            }
            .one()
            .map { routeObject -> parseLineString(routeObject) }
    }

    /** Parses GeoJSON LineString from ROUTE_OBJECT JSONB into list of [lat, lon] points. */
    private fun parseLineString(json: String?): CachedGeometry {
        if (json.isNullOrBlank()) {
            log.debug("Route has no ROUTE_OBJECT geometry")
            return CachedGeometry(emptyList(), System.currentTimeMillis())
        }

        return try {
            val root: JsonNode = objectMapper.readTree(json)
            val type = root.path("type").asText()
            val coords = root.path("coordinates")

            if (type != "LineString" || !coords.isArray || coords.isEmpty) {
                return CachedGeometry(emptyList(), System.currentTimeMillis())
            }

            val points = mutableListOf<DoubleArray>()
            for (node in coords) {
                if (node.isArray && node.size() >= 2) {
                    val lon = node.get(0).asDouble()
                    val lat = node.get(1).asDouble()
                    points.add(doubleArrayOf(lat, lon))
                }
            }
            CachedGeometry(points, System.currentTimeMillis())
        } catch (e: Exception) {
            log.warn("Failed to parse ROUTE_OBJECT geometry: {}", e.message)
            CachedGeometry(emptyList(), System.currentTimeMillis())
        }
    }

    /**
     * Snap a point to the nearest point on a polyline (projection onto nearest segment).
     *
     * Returns snapped [lat, lon] if within maxDistanceMeters, else null.
     */
    private fun snapToPoints(
        points: List<DoubleArray>,
        lat: Double,
        lon: Double,
        maxDistanceMeters: Double,
    ): DoubleArray? {
        if (points.isEmpty()) return null

        var bestDist = Double.MAX_VALUE
        var bestPoint = doubleArrayOf(lat, lon)

        if (points.size == 1) {
            return doubleArrayOf(points[0][0], points[0][1])
        }

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val projected = projectPointToSegment(lat, lon, a, b)
            val dist = haversineMeters(lat, lon, projected[0], projected[1])
            if (dist < bestDist) {
                bestDist = dist
                bestPoint = projected
            }
        }

        return if (bestDist <= maxDistanceMeters) bestPoint else null
    }

    /**
     * Project a point onto a segment (a-b). Returns closest [lat, lon] on the segment.
     * Uses equirectangular approximation locally (segment is short).
     */
    private fun projectPointToSegment(
        lat: Double, lon: Double,
        a: DoubleArray, b: DoubleArray,
    ): DoubleArray {
        val latScale = DEG_TO_M_LAT
        val lonScale = DEG_TO_M_LON * Math.cos(Math.toRadians(lat))

        // segment in local meters-ish (scaled degrees)
        val ax = a[1] * lonScale
        val ay = a[0] * latScale
        val bx = b[1] * lonScale
        val by = b[0] * latScale
        val px = lon * lonScale
        val py = lat * latScale

        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay

        val ab2 = abx * abx + aby * aby
        if (ab2 < 1e-12) return doubleArrayOf(a[0], a[1])

        var t = (apx * abx + apy * aby) / ab2
        t = t.coerceIn(0.0, 1.0)

        val latOut = (ay + t * aby) / latScale
        val lonOut = (ax + t * abx) / lonScale
        return doubleArrayOf(latOut, lonOut)
    }

    data class SnappedPoint(val latitude: Double, val longitude: Double)

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    companion object {
        private const val DEG_TO_M_LAT = 111_320.0
        private const val DEG_TO_M_LON = 78_800.0
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val TTL_MS = 5 * 60 * 1000L
    }
}
