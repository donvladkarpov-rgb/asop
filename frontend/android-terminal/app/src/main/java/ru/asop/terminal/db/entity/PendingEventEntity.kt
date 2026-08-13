package ru.asop.terminal.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "pending_events",
    indices = [
        androidx.room.Index("seq"),
        androidx.room.Index(value = ["status", "seq"])
    ]
)
data class PendingEventEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val payload: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "path_param") val pathParam: String? = null,
    val status: String = STATUS_PENDING,
    @ColumnInfo(name = "gateway_event_id") val gatewayEventId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "sent_at") val sentAt: Long? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,
    /**
     * Промпт 012: монотонный seq per-terminal, назначается SyncPreferences.nextSeq()
     * ПЕРЕД INSERT в эту таблицу. SyncWorker отправляет события в ORDER BY seq ASC
     * (see PendingEventDao.getPendingOrdered). Сервер использует (terminal_id, seq)
     * для watermark-based ordering.
     */
    @ColumnInfo(name = "seq") val seq: Long = 0L
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENDING = "SENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
    }
}
