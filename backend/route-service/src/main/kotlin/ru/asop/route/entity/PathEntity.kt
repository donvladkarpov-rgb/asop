package ru.asop.route.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Table("ASOP_PATHS")
data class PathEntity(
    @Id
    @Column("PATH_ID")
    val pathId: UUID = UuidUtils.newId(),
    @Column("ROUTE_ID")
    val routeId: UUID,
    @Column("PATH_NAME")
    val pathName: String,
    @Column("START_STOP_ID")
    val startStopId: UUID? = null,
    @Column("END_STOP_ID")
    val endStopId: UUID? = null,
    @Column("ROUTE_OBJECT")
    val routeObject: String? = null,
    @Column("BENEFIT_POLICY")
    val benefitPolicy: String,
    @Column("PATH_START_DATE")
    val pathStartDate: Instant? = null,
    @Column("PATH_END_DATE")
    val pathEndDate: Instant? = null,
    @Column("DESCRIPTION")
    val description: String? = null,
    @Column("REGION_ID")
    val regionId: UUID
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to pathId.toString(),
        "routeId" to routeId.toString(),
        "pathName" to pathName,
        "startStopId" to startStopId?.toString(),
        "endStopId" to endStopId?.toString(),
        "routeObject" to routeObject,
        "benefitPolicy" to benefitPolicy,
        "pathStartDate" to pathStartDate?.toString(),
        "pathEndDate" to pathEndDate?.toString(),
        "description" to description,
        "regionId" to regionId.toString()
    )

    fun toDbMap(): Map<String, Any?> = mapOf(
        "path_id" to pathId,
        "route_id" to routeId,
        "path_name" to pathName,
        "route_object" to routeObject,
        "benefit_policy" to benefitPolicy,
        "start_stop_id" to startStopId,
        "end_stop_id" to endStopId,
        "path_start_date" to pathStartDate,
        "path_end_date" to pathEndDate,
        "description" to description,
        "region_id" to regionId
    )

    companion object {
        fun fromRequest(data: Map<String, String?>): PathEntity = PathEntity(
            pathId = data["id"]?.let { UUID.fromString(it) } ?: UuidUtils.newId(),
            routeId = UUID.fromString(data["routeId"] ?: error("routeId is required")),
            pathName = data["pathName"] ?: error("pathName is required"),
            startStopId = data["startStopId"]?.let { UUID.fromString(it) },
            endStopId = data["endStopId"]?.let { UUID.fromString(it) },
            routeObject = data["routeObject"],
            benefitPolicy = data["benefitPolicy"] ?: "ALL",
            pathStartDate = data["pathStartDate"]?.let { Instant.parse(it) },
            pathEndDate = data["pathEndDate"]?.let { Instant.parse(it) },
            description = data["description"],
            regionId = UUID.fromString(data["regionId"] ?: error("regionId is required"))
        )

        fun fromDbRow(row: Map<String, Any?>): PathEntity = PathEntity(
            pathId = row["path_id"] as? UUID ?: error("path_id is required"),
            routeId = row["route_id"] as? UUID ?: error("route_id is required"),
            pathName = row["path_name"] as? String ?: error("path_name is required"),
            startStopId = row["start_stop_id"] as? UUID,
            endStopId = row["end_stop_id"] as? UUID,
            routeObject = row["route_object"] as? String,
            benefitPolicy = row["benefit_policy"] as? String ?: error("benefit_policy is required"),
            pathStartDate = row["path_start_date"] as? Instant,
            pathEndDate = row["path_end_date"] as? Instant,
            description = row["description"] as? String,
            regionId = row["region_id"] as? UUID ?: error("region_id is required")
        )
    }
}
