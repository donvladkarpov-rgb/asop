package ru.asop.route.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import ru.asop.common.util.UuidUtils
import java.time.LocalTime
import java.util.UUID

@Table("ASOP_SCHEDULE")
data class ScheduleEntity(
    @Id
    @Column("SCHEDULE_ID")
    val scheduleId: UUID = UuidUtils.newId(),
    @Column("PATH_ID")
    val pathId: UUID,
    @Column("STOP_ID")
    val stopId: UUID,
    @Column("DAY_MASK")
    val dayMask: Int,
    @Column("ARRIVAL_TIME")
    val arrivalTime: LocalTime,
    @Column("DWELL_TIME_SEC")
    val dwellTimeSec: Int? = null,
    @Column("REGION_ID")
    val regionId: UUID,
    @Column("IS_ACTIVE")
    val isActive: Boolean? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to scheduleId.toString(),
        "pathId" to pathId.toString(),
        "stopId" to stopId.toString(),
        "dayMask" to dayMask,
        "arrivalTime" to arrivalTime.toString(),
        "dwellTimeSec" to dwellTimeSec,
        "regionId" to regionId.toString(),
        "isActive" to isActive
    )

    fun toDbMap(): Map<String, Any?> = mapOf(
        "schedule_id" to scheduleId,
        "path_id" to pathId,
        "stop_id" to stopId,
        "day_mask" to dayMask,
        "arrival_time" to arrivalTime,
        "dwell_time_sec" to dwellTimeSec,
        "region_id" to regionId,
        "is_active" to isActive
    )

    companion object {
        fun fromRequest(data: Map<String, String?>): ScheduleEntity = ScheduleEntity(
            scheduleId = data["id"]?.let { UUID.fromString(it) } ?: UuidUtils.newId(),
            pathId = UUID.fromString(data["pathId"] ?: error("pathId is required")),
            stopId = UUID.fromString(data["stopId"] ?: error("stopId is required")),
            dayMask = data["dayMask"]?.toIntOrNull() ?: 127,
            arrivalTime = data["arrivalTime"]?.let { LocalTime.parse(it) } ?: error("arrivalTime is required"),
            dwellTimeSec = data["dwellTimeSec"]?.toIntOrNull(),
            regionId = UUID.fromString(data["regionId"] ?: error("regionId is required")),
            isActive = data["isActive"]?.toBoolean()
        )

        fun fromDbRow(row: Map<String, Any?>): ScheduleEntity = ScheduleEntity(
            scheduleId = row["schedule_id"] as? UUID ?: error("schedule_id is required"),
            pathId = row["path_id"] as? UUID ?: error("path_id is required"),
            stopId = row["stop_id"] as? UUID ?: error("stop_id is required"),
            dayMask = row["day_mask"] as? Int ?: error("day_mask is required"),
            arrivalTime = when (val v = row["arrival_time"]) {
                is LocalTime -> v
                is java.sql.Time -> v.toLocalTime()
                is String -> LocalTime.parse(v)
                else -> error("Cannot parse arrival_time: $v")
            },
            dwellTimeSec = row["dwell_time_sec"] as? Int,
            regionId = row["region_id"] as? UUID ?: error("region_id is required"),
            isActive = row["is_active"] as? Boolean
        )
    }
}
