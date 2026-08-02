package ru.asop.terminal.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Глобальный VERSION-водяной знак дельта-синхронизации.
 * Единственная строка (id = 0): последний version из sequence
 * (asop_delta_version_seq), обработанный терминалом.
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = 0,
    @ColumnInfo(name = "last_version")
    val lastVersion: Long? = null,
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long? = null
)
