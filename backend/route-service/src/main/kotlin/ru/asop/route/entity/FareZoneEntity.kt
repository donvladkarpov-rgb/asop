package ru.asop.route.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import ru.asop.common.util.UuidUtils
import java.util.UUID

@Table("ASOP_FARE_ZONES")
data class FareZoneEntity(
    @Id
    @Column("ZONE_ID")
    val zoneId: UUID = UuidUtils.newId(),
    @Column("ZONE_CODE")
    val zoneCode: String,
    @Column("ZONE_NAME")
    val zoneName: String,
    @Column("DESCRIPTION")
    val description: String? = null,
    @Column("ZONE_POLYGON")
    val zonePolygon: String? = null,
    @Column("REGION_ID")
    val regionId: UUID
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to zoneId.toString(),
        "zoneCode" to zoneCode,
        "zoneName" to zoneName,
        "description" to description,
        "zonePolygon" to zonePolygon,
        "regionId" to regionId.toString()
    )

    fun toDbMap(): Map<String, Any?> = mapOf(
        "zone_id" to zoneId,
        "zone_code" to zoneCode,
        "zone_name" to zoneName,
        "description" to description,
        "zone_polygon" to zonePolygon,
        "region_id" to regionId
    )

    companion object {
        fun fromRequest(data: Map<String, String?>): FareZoneEntity = FareZoneEntity(
            zoneId = data["id"]?.let { UUID.fromString(it) } ?: UuidUtils.newId(),
            zoneCode = data["zoneCode"] ?: error("zoneCode is required"),
            zoneName = data["zoneName"] ?: error("zoneName is required"),
            description = data["description"],
            zonePolygon = data["zonePolygon"],
            regionId = UUID.fromString(data["regionId"] ?: error("regionId is required"))
        )

        fun fromDbRow(row: Map<String, Any?>): FareZoneEntity = FareZoneEntity(
            zoneId = row["zone_id"] as? UUID ?: error("zone_id is required"),
            zoneCode = row["zone_code"] as? String ?: error("zone_code is required"),
            zoneName = row["zone_name"] as? String ?: error("zone_name is required"),
            description = row["description"] as? String,
            zonePolygon = row["zone_polygon"] as? String,
            regionId = row["region_id"] as? UUID ?: error("region_id is required")
        )
    }
}
