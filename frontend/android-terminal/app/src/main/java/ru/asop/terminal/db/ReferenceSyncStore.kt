package ru.asop.terminal.db

import androidx.room.withTransaction
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import ru.asop.terminal.db.dao.ReferenceRowDao
import ru.asop.terminal.db.dao.SyncMetaDao
import ru.asop.terminal.db.entity.ReferenceRowEntity
import ru.asop.terminal.db.entity.SyncMetaEntity
import ru.asop.proto.v1.DeltaChunk
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Атомарный накат дельта-чанков / полной выгрузки в Room.
 * Generic-хранение: каждая строка справочника — JSON (payloadJson),
 * ключ (tableName, rowId). rowId — значение PK (для составных — "id1|id2").
 */
@Singleton
class ReferenceSyncStore @Inject constructor(
    private val db: AppDatabase,
    private val referenceRowDao: ReferenceRowDao,
    private val syncMetaDao: SyncMetaDao
) {

    companion object {
        // Таблицы с составным PK (rowId = "field1|field2")
        private val COMPOSITE_KEY_TABLES = setOf(
            "asop_organizer_territories", "asop_contract_routes",
            "asop_user_roles", "asop_user_carriers", "asop_user_regions"
        )
    }

    /** Применяет один DeltaChunk (все таблицы в нём). */
    suspend fun applyChunk(chunk: DeltaChunk) {
        val rows = mutableListOf<ReferenceRowEntity>()
        val descriptor = DeltaChunk.getDescriptor()
        for (field in descriptor.fields) {
            val table = field.name
            val count = chunk.getRepeatedFieldCount(field)
            for (i in 0 until count) {
                rows += toReferenceRow(table, chunk.getRepeatedField(field, i) as Message)
            }
        }
        applyRows(rows)
    }

    /** Применяет файл `{table}.pb` из ZIP полной выгрузки (XxxFile message). */
    suspend fun applyFile(table: String, bytes: ByteArray) {
        val clsName = "ru.asop.proto.v1.${camel(table)}File"
        val builder = Class.forName(clsName).getMethod("newBuilder").invoke(null) as Message.Builder
        val msg = builder.mergeFrom(bytes).build()
        val rowsField = msg.descriptorForType.findFieldByName("rows")
            ?: return
        val rows = mutableListOf<ReferenceRowEntity>()
        for (i in 0 until msg.getRepeatedFieldCount(rowsField)) {
            rows += toReferenceRow(table, msg.getRepeatedField(rowsField, i) as Message)
        }
        applyRows(rows)
    }

    private suspend fun applyRows(rows: List<ReferenceRowEntity>) {
        if (rows.isEmpty()) return
        db.withTransaction {
            referenceRowDao.applyBatch(rows)
            // Обновляем sync_meta: MAX(updated_at) по каждой затронутой таблице
            val byTable = rows.groupBy { it.tableName }
            val now = System.currentTimeMillis()
            syncMetaDao.upsertAll(
                byTable.map { (table, tableRows) ->
                    SyncMetaEntity(
                        tableName = table,
                        lastUpdatedAt = tableRows.mapNotNull { it.updatedAt }.maxOrNull(),
                        lastSyncAt = now
                    )
                }
            )
        }
    }

    private fun toReferenceRow(table: String, row: Message): ReferenceRowEntity {
        val desc = row.descriptorForType
        val keyFields = if (table in COMPOSITE_KEY_TABLES) {
            desc.fields.take(2)
        } else {
            desc.fields.take(1)
        }
        val rowId = keyFields.joinToString("|") { field ->
            row.getField(field)?.toString() ?: ""
        }
        val updatedAt = stringField(row, desc, "updated_at")
        val deletedAt = stringField(row, desc, "deleted_at")
        return ReferenceRowEntity(
            tableName = table,
            rowId = rowId,
            payloadJson = JsonFormat.printer().print(row),
            updatedAt = updatedAt,
            deletedAt = deletedAt
        )
    }

    private fun stringField(row: Message, desc: com.google.protobuf.Descriptors.Descriptor, name: String): String? {
        val field = desc.findFieldByName(name) ?: return null
        if (field.javaType != FieldDescriptor.JavaType.STRING) return null
        val value = row.getField(field)?.toString() ?: return null
        return value.ifEmpty { null }
    }

    private fun camel(table: String): String {
        return table.removePrefix("asop_").split("_").joinToString("") { part ->
            part.replaceFirstChar { it.uppercase() }
        }
    }
}
