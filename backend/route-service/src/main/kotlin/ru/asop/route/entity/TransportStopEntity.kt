package ru.asop.route.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Table("ASOP_TRANSPORT_STOPS")
data class TransportStopEntity(
    @Id
    @Column("STOP_ID")
    val stopId: UUID = UuidUtils.newId(),
    @Column("FARE_ZONE_ID")
    val fareZoneId: UUID? = null,
    @Column("REGION_ID")
    val regionId: UUID,
    @Column("STOP_CODE")
    val stopCode: String,
    @Column("STOP_NAME")
    val stopName: String,
    @Column("STOP_ADDRESS")
    val stopAddress: String? = null,
    @Column("ZONE_POLYGON")
    val zonePolygon: String? = null,
    @Column("DESCRIPTION")
    val description: String? = null,
    @Column("IS_ACTIVE")
    val isActive: Boolean? = null,
    @Column("CREATED_AT")
    val createdAt: Instant = Instant.now(),
    @Column("UPDATED_AT")
    val updatedAt: Instant = Instant.now()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to stopId.toString(),
        "fareZoneId" to fareZoneId?.toString(),
        "regionId" to regionId.toString(),
        "stopCode" to stopCode,
        "stopName" to stopName,
        "stopAddress" to stopAddress,
        "zonePolygon" to zonePolygon,
        "description" to description,
        "isActive" to isActive,
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString()
    )

    fun toDbMap(): Map<String, Any?> = mapOf(
        "stop_id" to stopId,
        "fare_zone_id" to fareZoneId,
        "region_id" to regionId,
        "stop_code" to stopCode,
        "stop_name" to stopName,
        "stop_address" to stopAddress,
        "zone_polygon" to zonePolygon,
        "description" to description,
        "is_active" to isActive,
        "created_at" to createdAt,
        "updated_at" to Instant.now()
    )

    companion object {
        fun fromRequest(data: Map<String, String?>): TransportStopEntity = TransportStopEntity(
            stopId = data["id"]?.let { UUID.fromString(it) } ?: UuidUtils.newId(),
            fareZoneId = data["fareZoneId"]?.let { UUID.fromString(it) },
            regionId = UUID.fromString(data["regionId"] ?: error("regionId is required")),
            stopCode = data["stopCode"] ?: error("stopCode is required"),
            stopName = data["stopName"] ?: error("stopName is required"),
            stopAddress = data["stopAddress"],
            zonePolygon = data["zonePolygon"],
            description = data["description"],
            isActive = data["isActive"]?.toBoolean()
        )

        fun fromDbRow(row: Map<String, Any?>): TransportStopEntity = TransportStopEntity(
            stopId = row["stop_id"] as? UUID ?: error("stop_id is required"),
            fareZoneId = row["fare_zone_id"] as? UUID,
            regionId = row["region_id"] as? UUID ?: error("region_id is required"),
            stopCode = row["stop_code"] as? String ?: error("stop_code is required"),
            stopName = row["stop_name"] as? String ?: error("stop_name is required"),
            stopAddress = row["stop_address"] as? String,
            zonePolygon = row["zone_polygon"] as? String,
            description = row["description"] as? String,
            isActive = row["is_active"] as? Boolean,
            createdAt = row["created_at"] as? Instant ?: Instant.now(),
            updatedAt = row["updated_at"] as? Instant ?: Instant.now()
        )
    }
}
