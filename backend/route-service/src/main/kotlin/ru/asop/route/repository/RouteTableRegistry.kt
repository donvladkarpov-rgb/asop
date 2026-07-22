package ru.asop.route.repository

data class ResourceInfo(
    val resource: String,
    val tableName: String,
    val pkColumn: String,
    val idSnake: String,
    val columnExprs: Map<String, String> = emptyMap(),
    val selectColumns: String? = null
)

object RouteTableRegistry {

    private val ENTRIES: Map<String, ResourceInfo> = mapOf(
        "fare-zones" to ResourceInfo(
            resource = "fare-zones",
            tableName = "ASOP_FARE_ZONES",
            pkColumn = "ZONE_ID",
            idSnake = "zone_id",
            columnExprs = mapOf("zone_polygon" to "ST_GeomFromGeoJSON(NULLIF(:zone_polygon, ''))::geography"),
            selectColumns = "zone_id, zone_code, zone_name, description, ST_AsGeoJSON(zone_polygon)::text AS zone_polygon, region_id"
        ),
        "transport-stops" to ResourceInfo(
            resource = "transport-stops",
            tableName = "ASOP_TRANSPORT_STOPS",
            pkColumn = "STOP_ID",
            idSnake = "stop_id",
            columnExprs = mapOf("zone_polygon" to "ST_GeomFromGeoJSON(NULLIF(:zone_polygon, ''))::geography"),
            selectColumns = "stop_id, fare_zone_id, region_id, stop_code, stop_name, stop_address, ST_AsGeoJSON(zone_polygon)::text AS zone_polygon, description, is_active, created_at, updated_at"
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
        ),
        "vehicle-types" to ResourceInfo(
            resource = "vehicle-types",
            tableName = "ASOP_VEHICLE_TYPES",
            pkColumn = "VEHICLE_TYPE_ID",
            idSnake = "vehicle_type_id"
        ),
        "vehicle-models" to ResourceInfo(
            resource = "vehicle-models",
            tableName = "ASOP_VEHICLE_MODELS",
            pkColumn = "VEHICLE_MODEL_ID",
            idSnake = "vehicle_model_id"
        ),
        "contract-routes" to ResourceInfo(
            resource = "contract-routes",
            tableName = "ASOP_CONTRACT_ROUTES",
            pkColumn = "CONTRACT_ID",
            idSnake = "contract_id"
        )
    )

    fun resolve(resource: String): ResourceInfo? = ENTRIES[resource]

    fun all(): Set<String> = ENTRIES.keys

    fun allEntries(): List<ResourceInfo> = ENTRIES.values.toList()
}
