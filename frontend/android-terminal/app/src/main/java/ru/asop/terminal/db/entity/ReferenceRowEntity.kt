package ru.asop.terminal.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Generic-строка справочника из дельта-синхронизации.
 * Хранится как JSON (payload) — единая таблица на все таблицы-справочники,
 * ключ (tableName, rowId). rowId — значение PK (для составных PK — "id1|id2").
 */
@Entity(
    tableName = "reference_rows",
    primaryKeys = ["table_name", "row_id"],
    indices = [Index("table_name"), Index("updated_at"), Index("deleted_at")]
)
data class ReferenceRowEntity(
    @ColumnInfo(name = "table_name")
    val tableName: String,
    @ColumnInfo(name = "row_id")
    val rowId: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String? = null,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null
)
