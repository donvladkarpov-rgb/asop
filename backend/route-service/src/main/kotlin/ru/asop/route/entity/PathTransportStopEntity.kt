package ru.asop.route.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Table("ASOP_PATH_TRANSPORT_STOPS")
data class PathTransportStopEntity(
    @Id
    @Column("PATH_STOP_ID")
    val pathStopId: UUID = UuidUtils.newId(),
    @Column("PATH_ID")
    val pathId: UUID,
    @Column("STOP_ID")
    val stopId: UUID,
    @Column("SERIAL_NUMBER")
    val serialNumber: Int,
    @Column("REGION_ID")
    val regionId: UUID,
    @Column("CREATED_AT")
    val createdAt: Instant = Instant.now(),
    @Column("UPDATED_AT")
    val updatedAt: Instant = Instant.now()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to pathStopId.toString(),
        "pathId" to pathId.toString(),
        "stopId" to stopId.toString(),
        "serialNumber" to serialNumber,
        "regionId" to regionId.toString(),
        "createdAt" to createdAt.toString(),
        "updatedAt" to updatedAt.toString()
    )

    fun toDbMap(): Map<String, Any?> = mapOf(
        "path_stop_id" to pathStopId,
        "path_id" to pathId,
        "stop_id" to stopId,
        "serial_number" to serialNumber,
        "region_id" to regionId,
        "created_at" to Instant.now(),
        "updated_at" to Instant.now()
    )

    companion object {
        fun fromRequest(data: Map<String, String?>): PathTransportStopEntity = PathTransportStopEntity(
            pathStopId = data["id"]?.let { UUID.fromString(it) } ?: UuidUtils.newId(),
            pathId = UUID.fromString(data["pathId"] ?: error("pathId is required")),
            stopId = UUID.fromString(data["stopId"] ?: error("stopId is required")),
            serialNumber = data["serialNumber"]?.toIntOrNull() ?: error("serialNumber is required"),
            regionId = UUID.fromString(data["regionId"] ?: error("regionId is required"))
        )

        fun fromDbRow(row: Map<String, Any?>): PathTransportStopEntity = PathTransportStopEntity(
            pathStopId = row["path_stop_id"] as? UUID ?: error("path_stop_id is required"),
            pathId = row["path_id"] as? UUID ?: error("path_id is required"),
            stopId = row["stop_id"] as? UUID ?: error("stop_id is required"),
            serialNumber = row["serial_number"] as? Int ?: error("serial_number is required"),
            regionId = row["region_id"] as? UUID ?: error("region_id is required"),
            createdAt = row["created_at"] as? Instant ?: Instant.now(),
            updatedAt = row["updated_at"] as? Instant ?: Instant.now()
        )
    }
}
