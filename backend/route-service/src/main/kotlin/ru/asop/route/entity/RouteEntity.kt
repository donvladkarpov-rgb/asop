package ru.asop.route.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import ru.asop.common.util.UuidUtils
import java.util.UUID

@Table("ASOP_ROUTES")
data class RouteEntity(
    @Id
    @Column("ROUTE_ID")
    val routeId: UUID = UuidUtils.newId(),
    @Column("ROUTE_NUMBER")
    val routeNumber: String,
    @Column("ROUTE_NAME")
    val routeName: String,
    @Column("ROUTE_CATEGORY")
    val routeCategory: String,
    @Column("ORGANIZER_ID")
    val organizerId: UUID? = null,
    @Column("MINISTRY_REGISTRY_NO")
    val ministryRegistryNo: String? = null,
    @Column("REGION_ID")
    val regionId: UUID
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to routeId.toString(),
        "routeNumber" to routeNumber,
        "routeName" to routeName,
        "routeCategory" to routeCategory,
        "organizerId" to organizerId?.toString(),
        "ministryRegistryNo" to ministryRegistryNo,
        "regionId" to regionId.toString()
    )

    fun toDbMap(): Map<String, Any?> = mapOf(
        "route_id" to routeId,
        "route_number" to routeNumber,
        "route_name" to routeName,
        "route_category" to routeCategory,
        "organizer_id" to organizerId,
        "ministry_registry_no" to ministryRegistryNo,
        "region_id" to regionId
    )

    companion object {
        fun fromRequest(data: Map<String, String?>): RouteEntity = RouteEntity(
            routeId = data["id"]?.let { UUID.fromString(it) } ?: UuidUtils.newId(),
            routeNumber = data["routeNumber"] ?: error("routeNumber is required"),
            routeName = data["routeName"] ?: error("routeName is required"),
            routeCategory = data["routeCategory"] ?: error("routeCategory is required"),
            organizerId = data["organizerId"]?.takeIf { it.isNotBlank() }?.let { UUID.fromString(it) },
            ministryRegistryNo = data["ministryRegistryNo"],
            regionId = UUID.fromString(data["regionId"] ?: error("regionId is required"))
        )

        fun fromDbRow(row: Map<String, Any?>): RouteEntity = RouteEntity(
            routeId = row["route_id"] as? UUID ?: error("route_id is required"),
            routeNumber = row["route_number"] as? String ?: error("route_number is required"),
            routeName = row["route_name"] as? String ?: error("route_name is required"),
            routeCategory = row["route_category"] as? String ?: error("route_category is required"),
            organizerId = row["organizer_id"] as? UUID,
            ministryRegistryNo = row["ministry_registry_no"] as? String,
            regionId = row["region_id"] as? UUID ?: error("region_id is required")
        )
    }
}
