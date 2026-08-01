package ru.asop.terminal.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Метка синхронизации по таблице справочника.
 * deltaSync шлёт Map<tableName, lastUpdatedAt>; после успешного apply
 * терминал сохраняет максимальный updated_at по таблице.
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "table_name")
    val tableName: String,
    @ColumnInfo(name = "last_updated_at")
    val lastUpdatedAt: String? = null,
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long? = null
)
