package ru.asop.route.controller

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import java.util.UUID

data class StopDto(
    val stopId: UUID,
    val stopName: String,
    val stopCode: String,
    val stopAddress: String?,
    val regionId: UUID,
    val latitude: Double,
    val longitude: Double
)

@RestController
@RequestMapping("/api/v1/stops")
class StopsBboxController(private val db: DatabaseClient) {

    @GetMapping("/bbox")
    fun getStopsInBBox(
        @RequestParam southWest: String,
        @RequestParam northEast: String,
        @RequestParam(required = false) regionId: UUID?
    ): Flux<StopDto> {
        val sw = southWest.split(",").map { it.trim().toDouble() }
        val ne = northEast.split(",").map { it.trim().toDouble() }
        val west = sw[1]; val south = sw[0]; val east = ne[1]; val north = ne[0]

        val sql = """
            SELECT STOP_ID, STOP_NAME, STOP_CODE, STOP_ADDRESS, REGION_ID,
                   ST_Y(ST_Centroid(ZONE_POLYGON)::geometry) AS latitude,
                   ST_X(ST_Centroid(ZONE_POLYGON)::geometry) AS longitude
            FROM ASOP_TRANSPORT_STOPS
            WHERE ST_Within(ZONE_POLYGON::geometry, ST_MakeEnvelope(:west, :south, :east, :north, 4326))
              AND IS_ACTIVE = true AND DELETED_AT IS NULL
              AND (:regionId::UUID IS NULL OR REGION_ID = :regionId)
            ORDER BY STOP_NAME
        """.trimIndent()

        return db.sql(sql)
            .bind("west", west)
            .bind("south", south)
            .bind("east", east)
            .bind("north", north)
            .let { spec ->
                if (regionId != null) spec.bind("regionId", regionId)
                else spec.bindNull("regionId", UUID::class.java)
            }
            .map { row, _ ->
                StopDto(
                    stopId = row.get("STOP_ID") as UUID,
                    stopName = row.get("STOP_NAME")?.toString() ?: "",
                    stopCode = row.get("STOP_CODE")?.toString() ?: "",
                    stopAddress = row.get("STOP_ADDRESS")?.toString(),
                    regionId = row.get("REGION_ID") as UUID,
                    latitude = (row.get("latitude") as? Number)?.toDouble() ?: 0.0,
                    longitude = (row.get("longitude") as? Number)?.toDouble() ?: 0.0
                )
            }
            .all()
    }
}
