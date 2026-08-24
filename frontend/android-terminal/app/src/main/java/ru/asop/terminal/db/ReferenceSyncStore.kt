package ru.asop.terminal.db

import androidx.room.withTransaction
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import ru.asop.terminal.db.dao.ReferenceRowDao
import ru.asop.terminal.db.dao.SyncMetaDao
import ru.asop.terminal.db.dao.TerminalKeyDao
import ru.asop.terminal.db.entity.ReferenceRowEntity
import ru.asop.terminal.db.entity.SyncMetaEntity
import ru.asop.terminal.db.entity.TerminalKeyEntity
import ru.asop.proto.v1.AsopKeysFile
import ru.asop.proto.v1.DeltaChunk
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Атомарный накат дельта-чанков / полной выгрузки в Room.
 * Generic-хранение: каждая строка справочника — JSON (payloadJson),
 * ключ (tableName, rowId). rowId — значение PK (для составных — "id1|id2").
 * sync_meta — single-row глобальный VERSION-водяной знак.
 * Ключи (asop_keys) хранятся отдельно в terminal_keys, зашифрованными
 * локальным Keystore-AES-ключом (НЕ в reference_rows — иначе ломается водяной знак).
 */
@Singleton
class ReferenceSyncStore @Inject constructor(
    private val db: AppDatabase,
    private val referenceRowDao: ReferenceRowDao,
    private val syncMetaDao: SyncMetaDao,
    private val terminalKeyDao: TerminalKeyDao,
    private val terminalKeyCryptor: TerminalKeyCryptor
) {

    companion object {
        // Таблицы с составным PK (rowId = "field1|field2")
        private val COMPOSITE_KEY_TABLES = setOf(
            "asop_organizer_territories", "asop_contract_routes",
            "asop_user_roles", "asop_user_carriers", "asop_user_regions",
            "asop_user_cards_distributors", "asop_user_krs"
        )
        const val KEYS_TABLE = "asop_keys"
        private const val UPSERT_CHUNK = 2000
    }

    /** Применяет один DeltaChunk (все таблицы в нём). */
    suspend fun applyChunk(chunk: DeltaChunk) {
        val refRows = mutableListOf<ReferenceRowEntity>()
        val keyRows = mutableListOf<TerminalKeyEntity>()
        val descriptor = DeltaChunk.getDescriptor()
        for (field in descriptor.fields) {
            if (!field.isRepeated) continue
            val table = field.name
            val count = chunk.getRepeatedFieldCount(field)
            for (i in 0 until count) {
                val msg = chunk.getRepeatedField(field, i) as Message
                if (table == KEYS_TABLE) {
                    keyRows += toTerminalKeyRow(msg)
                } else {
                    refRows += toReferenceRow(table, msg)
                }
            }
        }
        applyRows(refRows, keyRows)
    }

    /**
     * Полная выкачка: АТОМАРНАЯ ЗАМЕНА всего справочного набора. Все .pb-файлы
     * парсятся, затем в ОДНОЙ транзакции: очистка reference_rows → накат свежих
     * строк (чанками) → watermark = MAX(version) свежих строк.
     * Зачем очистка: терминал мог синкаться с другой/пересозданной БД — его watermark
     * и строки опережают сервер (дельта вечно возвращает 0 чанков), а мёртвые строки
     * (несуществующие UUID) иначе остаются навсегда. Полная выкачка = чистый слейт.
     * terminal_keys НЕ очищаем — их жизненный цикл независим (ротация ключей).
     */
    suspend fun applyFullDump(files: Map<String, ByteArray>) {
        val refRows = mutableListOf<ReferenceRowEntity>()
        val keyRows = mutableListOf<TerminalKeyEntity>()
        for ((table, bytes) in files) {
            if (table == KEYS_TABLE) {
                val msg = AsopKeysFile.newBuilder().mergeFrom(bytes).build()
                keyRows += msg.rowsList.map { toTerminalKeyRow(it) }
                continue
            }
            val clsName = "ru.asop.proto.v1.${camel(table)}File"
            val builder = runCatching {
                Class.forName(clsName).getMethod("newBuilder").invoke(null) as Message.Builder
            }.getOrNull() ?: continue
            val msg = builder.mergeFrom(bytes).build()
            val rowsField = msg.descriptorForType.findFieldByName("rows") ?: continue
            for (i in 0 until msg.getRepeatedFieldCount(rowsField)) {
                refRows += toReferenceRow(table, msg.getRepeatedField(rowsField, i) as Message)
            }
        }
        db.withTransaction {
            referenceRowDao.clearAll()
            refRows.chunked(UPSERT_CHUNK).forEach { referenceRowDao.upsertAll(it) }
            if (keyRows.isNotEmpty()) terminalKeyDao.applyBatch(keyRows)
            val maxVersion = referenceRowDao.maxVersion()
            if (maxVersion != null) {
                syncMetaDao.upsert(
                    SyncMetaEntity(
                        id = 0,
                        lastVersion = maxVersion,
                        lastSyncAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /** Применяет файл `{table}.pb` из ZIP полной выгрузки (XxxFile message). */
    suspend fun applyFile(table: String, bytes: ByteArray) {        if (table == KEYS_TABLE) {
            val builder = AsopKeysFile.newBuilder()
            val msg = builder.mergeFrom(bytes).build()
            val keyRows = msg.rowsList.map { toTerminalKeyRow(it) }
            applyRows(emptyList(), keyRows)
            return
        }
        val clsName = "ru.asop.proto.v1.${camel(table)}File"
        val builder = Class.forName(clsName).getMethod("newBuilder").invoke(null) as Message.Builder
        val msg = builder.mergeFrom(bytes).build()
        val rowsField = msg.descriptorForType.findFieldByName("rows")
            ?: return
        val rows = mutableListOf<ReferenceRowEntity>()
        for (i in 0 until msg.getRepeatedFieldCount(rowsField)) {
            rows += toReferenceRow(table, msg.getRepeatedField(rowsField, i) as Message)
        }
        applyRows(rows, emptyList())
    }

    private suspend fun applyRows(refRows: List<ReferenceRowEntity>, keyRows: List<TerminalKeyEntity>) {
        if (refRows.isEmpty() && keyRows.isEmpty()) return
        db.withTransaction {
            if (refRows.isNotEmpty()) referenceRowDao.applyBatch(refRows)
            if (keyRows.isNotEmpty()) terminalKeyDao.applyBatch(keyRows)
        }
    }

    /**
     * Полная выкачка/дельта завершены: поднимаем watermark до глобального MAX(version).
     * Водяной знак учитывает только reference_rows; ключи (terminal_keys)
     * не участвуют в watermark — они накатываются на каждый дельта независимо.
     */
    suspend fun updateGlobalWatermark() {
        val maxVersion = referenceRowDao.maxVersion() ?: return
        syncMetaDao.upsert(
            SyncMetaEntity(
                id = 0,
                lastVersion = maxVersion,
                lastSyncAt = System.currentTimeMillis()
            )
        )
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
        val version = longField(row, desc, "version")
        return ReferenceRowEntity(
            tableName = table,
            rowId = rowId,
            payloadJson = JsonFormat.printer().print(row),
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            version = version
        )
    }

    /** Ключ из asop_keys: перешифровываем plaintext локальным Keystore-AES-ключом at-rest. */
    private fun toTerminalKeyRow(msg: Message): TerminalKeyEntity {
        val desc = msg.descriptorForType
        val keyMaterial = (msg.getField(desc.findFieldByName("key_material")) as? com.google.protobuf.ByteString)
            ?.toByteArray() ?: ByteArray(0)
        return TerminalKeyEntity(
            keyId = msg.getField(desc.findFieldByName("key_id"))?.toString() ?: "",
            keyMaterialEnc = terminalKeyCryptor.encrypt(keyMaterial),
            createdAt = (msg.getField(desc.findFieldByName("created_at")) as? Number)?.toLong() ?: 0L,
            deletedAt = (msg.getField(desc.findFieldByName("deleted_at")) as? Number)?.toLong() ?: 0L,
            version = (msg.getField(desc.findFieldByName("version")) as? Number)?.toLong() ?: 0L
        )
    }

    private fun stringField(row: Message, desc: com.google.protobuf.Descriptors.Descriptor, name: String): String? {
        val field = desc.findFieldByName(name) ?: return null
        if (field.javaType != FieldDescriptor.JavaType.STRING) return null
        val value = row.getField(field)?.toString() ?: return null
        return value.ifEmpty { null }
    }

    private fun longField(row: Message, desc: com.google.protobuf.Descriptors.Descriptor, name: String): Long? {
        val field = desc.findFieldByName(name) ?: return null
        if (field.javaType != FieldDescriptor.JavaType.LONG) return null
        return (row.getField(field) as? Number)?.toLong()
    }

    private fun camel(table: String): String {
        return table.removePrefix("asop_").split("_").joinToString("") { part ->
            part.replaceFirstChar { it.uppercase() }
        }
    }
}