package ru.asop.session.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import ru.asop.session.dto.LiveVehicleDto
import ru.asop.session.dto.TrackPointDto
import ru.asop.session.gps.GpsRouteSnapper
import java.util.UUID

@Service
class TrackingService(
    private val db: DatabaseClient,
    private val routeSnapper: GpsRouteSnapper,
) {

    fun getLiveVehicles(regionId: UUID?, carrierId: UUID?, vehicleId: UUID?, freshSec: Int): Flux<LiveVehicleDto> {
        val sql = """
            WITH latest AS (
                SELECT DISTINCT ON (g.VEHICLE_ID)
                    g.VEHICLE_ID, g.GPS_COORD, g.RECORDED_AT, g.SPEED_KMH, g.PATH_ID, g.SESSION_ID
                FROM ASOP_GPS_TRACKING g
                WHERE g.RECORDED_AT > NOW() - (:freshSec || ' seconds')::INTERVAL
                  AND (:vehicleId::UUID IS NULL OR g.VEHICLE_ID = :vehicleId)
                ORDER BY g.VEHICLE_ID, g.RECORDED_AT DESC
            )
            SELECT l.VEHICLE_ID,
                   ST_Y(l.GPS_COORD::geometry) AS latitude,
                   ST_X(l.GPS_COORD::geometry) AS longitude,
                   l.RECORDED_AT, l.SPEED_KMH, l.PATH_ID, l.SESSION_ID,
                   v.VEHICLE_NUMBER, v.VEHICLE_NAME,
                    vh.TYPE_NAME AS VEHICLE_TYPE_NAME,
                   p.PATH_NAME, p.ROUTE_ID
            FROM latest l
            JOIN ASOP_VEHICLES v ON v.VEHICLE_ID = l.VEHICLE_ID
            LEFT JOIN ASOP_PATHS p ON p.PATH_ID = l.PATH_ID
            LEFT JOIN ASOP_VEHICLE_TYPES vh ON vh.VEHICLE_TYPE_ID = v.VEHICLE_TYPE_ID
            WHERE (:carrierId::UUID IS NULL OR v.CARRIER_ID = :carrierId)
              AND (:regionId::UUID IS NULL
                   OR v.CARRIER_ID IN (SELECT CARRIER_ID FROM ASOP_CARRIERS WHERE REGION_ID = :regionId))
            ORDER BY l.RECORDED_AT DESC
        """.trimIndent()

        return db.sql(sql)
            .bind("freshSec", freshSec)
            .let { spec ->
                if (vehicleId != null) spec.bind("vehicleId", vehicleId)
                else spec.bindNull("vehicleId", UUID::class.java)
            }
            .let { spec ->
                if (carrierId != null) spec.bind("carrierId", carrierId)
                else spec.bindNull("carrierId", UUID::class.java)
            }
            .let { spec ->
                if (regionId != null) spec.bind("regionId", regionId)
                else spec.bindNull("regionId", UUID::class.java)
            }
            .map { row, _ ->
                val vLat = (row.get("latitude") as? Number)?.toDouble() ?: 0.0
                val vLon = (row.get("longitude") as? Number)?.toDouble() ?: 0.0
                val pathId = row.get("PATH_ID") as? UUID
                SnappableVehicle(
                    vehicleId = row.get("VEHICLE_ID") as UUID,
                    vehicleNumber = row.get("VEHICLE_NUMBER")?.toString() ?: "",
                    vehicleName = row.get("VEHICLE_NAME")?.toString() ?: "",
                    vehicleType = row.get("VEHICLE_TYPE_NAME")?.toString() ?: "",
                    latitude = vLat,
                    longitude = vLon,
                    speedKmh = (row.get("SPEED_KMH") as? Number)?.toDouble(),
                    recordedAt = row.get("RECORDED_AT")?.toString() ?: "",
                    pathId = pathId,
                    pathName = row.get("PATH_NAME")?.toString(),
                    routeId = row.get("ROUTE_ID") as? UUID,
                    sessionId = row.get("SESSION_ID") as? UUID
                )
            }
            .all()
            .flatMap { v ->
                val dto = { snapped: GpsRouteSnapper.SnappedPoint? ->
                    LiveVehicleDto(
                        vehicleId = v.vehicleId,
                        vehicleNumber = v.vehicleNumber,
                        vehicleName = v.vehicleName,
                        vehicleType = v.vehicleType,
                        latitude = v.latitude,
                        longitude = v.longitude,
                        snappedLatitude = snapped?.latitude,
                        snappedLongitude = snapped?.longitude,
                        speedKmh = v.speedKmh,
                        recordedAt = v.recordedAt,
                        pathId = v.pathId,
                        pathName = v.pathName,
                        routeId = v.routeId,
                        sessionId = v.sessionId
                    )
                }
                findSnapped(v.pathId, v.latitude, v.longitude)
                    .map { snapped -> dto(snapped) }
                    .defaultIfEmpty(dto(null))
            }
    }

    fun getVehicleTrack(vehicleId: UUID, minutes: Int): Flux<TrackPointDto> {
        val sql = """
            SELECT ST_Y(GPS_COORD::geometry) AS latitude,
                   ST_X(GPS_COORD::geometry) AS longitude,
                   RECORDED_AT, SPEED_KMH, PATH_ID
            FROM ASOP_GPS_TRACKING
            WHERE VEHICLE_ID = :vehicleId
              AND RECORDED_AT > NOW() - (:minutes || ' minutes')::INTERVAL
            ORDER BY RECORDED_AT ASC
        """.trimIndent()

        return db.sql(sql)
            .bind("vehicleId", vehicleId)
            .bind("minutes", minutes)
            .map { row, _ ->
                val tLat = (row.get("latitude") as? Number)?.toDouble() ?: 0.0
                val tLon = (row.get("longitude") as? Number)?.toDouble() ?: 0.0
                val pathId = row.get("PATH_ID") as? UUID
                SnappablePoint(
                    latitude = tLat,
                    longitude = tLon,
                    recordedAt = row.get("RECORDED_AT")?.toString() ?: "",
                    speedKmh = (row.get("SPEED_KMH") as? Number)?.toDouble(),
                    pathId = pathId
                )
            }
            .all()
            .flatMap { p ->
                findSnapped(p.pathId, p.latitude, p.longitude)
                    .map { snapped ->
                        TrackPointDto(
                            latitude = p.latitude,
                            longitude = p.longitude,
                            snappedLatitude = snapped?.latitude,
                            snappedLongitude = snapped?.longitude,
                            recordedAt = p.recordedAt,
                            speedKmh = p.speedKmh
                        )
                    }
            }
    }

    private fun findSnapped(pathId: UUID?, lat: Double, lon: Double) =
        routeSnapper.snap(pathId, lat, lon)

    private data class SnappableVehicle(
        val vehicleId: UUID,
        val vehicleNumber: String,
        val vehicleName: String,
        val vehicleType: String,
        val latitude: Double,
        val longitude: Double,
        val speedKmh: Double?,
        val recordedAt: String,
        val pathId: UUID?,
        val pathName: String?,
        val routeId: UUID?,
        val sessionId: UUID?,
    )

    private data class SnappablePoint(
        val latitude: Double,
        val longitude: Double,
        val recordedAt: String,
        val speedKmh: Double?,
        val pathId: UUID?,
    )
}
