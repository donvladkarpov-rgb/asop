package ru.asop.route.controller

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import java.util.UUID

data class RouteByStopDto(
    val routeId: UUID,
    val routeName: String,
    val routeNumber: String?,
    val pathId: UUID,
    val pathName: String
)

@RestController
@RequestMapping("/api/v1/stops")
class StopRoutesController(private val db: DatabaseClient) {

    @GetMapping("/{stopId}/routes")
    fun getRoutesForStop(@PathVariable stopId: UUID): Flux<RouteByStopDto> {
        val sql = """
            SELECT DISTINCT r.ROUTE_ID, r.ROUTE_NAME, r.ROUTE_NUMBER,
                   p.PATH_ID, p.PATH_NAME
            FROM ASOP_PATH_TRANSPORT_STOPS pts
            JOIN ASOP_PATHS p ON p.PATH_ID = pts.PATH_ID
            JOIN ASOP_ROUTES r ON r.ROUTE_ID = p.ROUTE_ID
            WHERE pts.STOP_ID = :stopId AND p.DELETED_AT IS NULL AND r.DELETED_AT IS NULL
            ORDER BY r.ROUTE_NUMBER
        """.trimIndent()

        return db.sql(sql)
            .bind("stopId", stopId)
            .map { row, _ ->
                RouteByStopDto(
                    routeId = row.get("ROUTE_ID") as UUID,
                    routeName = row.get("ROUTE_NAME")?.toString() ?: "",
                    routeNumber = row.get("ROUTE_NUMBER")?.toString(),
                    pathId = row.get("PATH_ID") as UUID,
                    pathName = row.get("PATH_NAME")?.toString() ?: ""
                )
            }
            .all()
    }
}
