package ru.asop.route.repository

data class ResourceInfo(
    val resource: String,
    val tableName: String,
    val pkColumn: String,
    val idSnake: String,
    val columnExprs: Map<String, String> = emptyMap()
)

object RouteTableRegistry {

    private val ENTRIES: Map<String, ResourceInfo> = mapOf(
        "fare-zones" to ResourceInfo(
            resource = "fare-zones",
            tableName = "ASOP_FARE_ZONES",
            pkColumn = "ZONE_ID",
            idSnake = "zone_id",
            columnExprs = mapOf("zone_polygon" to "ST_GeogFromGeoJSON(:zone_polygon)")
        ),
        "transport-stops" to ResourceInfo(
            resource = "transport-stops",
            tableName = "ASOP_TRANSPORT_STOPS",
            pkColumn = "STOP_ID",
            idSnake = "stop_id",
            columnExprs = mapOf("zone_polygon" to "ST_GeogFromGeoJSON(:zone_polygon)")
        ),
        "routes" to ResourceInfo(
            resource = "routes",
            tableName = "ASOP_ROUTES",
            pkColumn = "ROUTE_ID",
            idSnake = "route_id"
        ),
        "paths" to ResourceInfo(
            resource = "paths",
            tableName = "ASOP_PATHS",
            pkColumn = "PATH_ID",
            idSnake = "path_id",
            columnExprs = mapOf("route_object" to "CAST(:route_object AS jsonb)")
        ),
        "path-transport-stops" to ResourceInfo(
            resource = "path-transport-stops",
            tableName = "ASOP_PATH_TRANSPORT_STOPS",
            pkColumn = "PATH_STOP_ID",
            idSnake = "path_stop_id"
        ),
        "schedule" to ResourceInfo(
            resource = "schedule",
            tableName = "ASOP_SCHEDULE",
            pkColumn = "SCHEDULE_ID",
            idSnake = "schedule_id"
        ),
        "path-services" to ResourceInfo(
            resource = "path-services",
            tableName = "ASOP_PATH_SERVICES",
            pkColumn = "PATH_SERVICE_ID",
            idSnake = "path_service_id"
        ),
        "path-discounts" to ResourceInfo(
            resource = "path-discounts",
            tableName = "ASOP_PATH_DISCOUNTS",
            pkColumn = "PATH_DISCOUNT_ID",
            idSnake = "path_discount_id"
        ),
        "path-benefits" to ResourceInfo(
            resource = "path-benefits",
            tableName = "ASOP_PATH_BENEFITS",
            pkColumn = "PATH_BENEFIT_ID",
            idSnake = "path_benefit_id"
        ),
        "vehicles" to ResourceInfo(
            resource = "vehicles",
            tableName = "ASOP_VEHICLES",
            pkColumn = "VEHICLE_ID",
            idSnake = "vehicle_id"
        )
    )

    fun resolve(resource: String): ResourceInfo? = ENTRIES[resource]

    fun all(): Set<String> = ENTRIES.keys

    fun allEntries(): List<ResourceInfo> = ENTRIES.values.toList()
}
