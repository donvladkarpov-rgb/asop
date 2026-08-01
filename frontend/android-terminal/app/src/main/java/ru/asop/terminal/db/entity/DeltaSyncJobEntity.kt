package ru.asop.terminal.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Задание дельта-синхронизации. DeltaSyncWorker создаёт запись (PENDING),
 * DeltaChunkPollWorker скачивает чанки и при успехе ставит COMPLETED,
 * при ошибке удаляет строку (следующий час DeltaSyncWorker перезапросит).
 */
@Entity(tableName = "delta_sync_jobs")
data class DeltaSyncJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    val status: String = "PENDING",
    @ColumnInfo(name = "requested_at")
    val requestedAt: Long,
    @ColumnInfo(name = "total_chunks")
    val totalChunks: Int? = null,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)
