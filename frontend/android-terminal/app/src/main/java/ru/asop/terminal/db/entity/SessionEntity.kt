package ru.asop.terminal.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Промпт 011: иерархические сессии (SHIFT → TRIP).
 * Android-компаньон ASOP_SESSIONS + дополнительные поля carrierId/regionId,
 * которые локально резолвятся из user_carriers/user_regions на момент open.
 */
@Entity(
    tableName = "sessions",
    indices = [
        Index("status"),
        Index("session_type_code"),
        Index("parent_session_id"),
        Index("opened_by_user_id")
    ]
)
data class SessionEntity(
    @PrimaryKey val id: String,                                  // UUIDv7
    @ColumnInfo(name = "session_type_code") val sessionTypeCode: String, // "SHIFT" | "TRIP" | "BREAK"
    @ColumnInfo(name = "session_type_id") val sessionTypeId: String,   // ASOP_SESSION_TYPES.SESSION_TYPE_ID
    @ColumnInfo(name = "parent_session_id") val parentSessionId: String? = null,
    @ColumnInfo(name = "terminal_id") val terminalId: String? = null,
    @ColumnInfo(name = "tid_id") val tidId: String? = null,
    @ColumnInfo(name = "opened_by_user_id") val openedByUserId: String? = null,
    @ColumnInfo(name = "closed_by_user_id") val closedByUserId: String? = null,
    @ColumnInfo(name = "card_id") val cardId: String? = null,
    @ColumnInfo(name = "path_id") val pathId: String? = null,
    @ColumnInfo(name = "vehicle_id") val vehicleId: String? = null,
    @ColumnInfo(name = "carrier_id") val carrierId: String? = null,
    @ColumnInfo(name = "region_id") val regionId: String? = null,
    @ColumnInfo(name = "timezone") val timezone: String? = null,
    val status: String,
    @ColumnInfo(name = "opened_at") val openedAt: Long,
    @ColumnInfo(name = "closed_at") val closedAt: Long? = null,
    @ColumnInfo(name = "opened_at_local") val openedAtLocal: Long,
    @ColumnInfo(name = "closed_at_local") val closedAtLocal: Long? = null,
    @ColumnInfo(name = "expiration_time") val expirationTime: Long,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    val attributes: String? = null
) {
    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_CLOSED = "CLOSED"

        const val TYPE_SHIFT = "SHIFT"
        const val TYPE_TRIP = "TRIP"
        const val TYPE_BREAK = "BREAK"

        const val DEFAULT_EXPIRATION_HOURS = 8L
    }
}
