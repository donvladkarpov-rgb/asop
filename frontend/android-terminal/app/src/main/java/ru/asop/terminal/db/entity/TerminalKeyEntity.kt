package ru.asop.terminal.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ключ карты на терминале (глобальный пул ASOP_KEYS, доставленный по дельте/полной выкачке).
 * KEY_MATERIAL_ENC — материал ключа, зашифрованный локальным Keystore-AES-ключом
 * (PURPOSE_ENCRYPT|PURPOSE_DECRYPT, неэкспортируемый). В открытом виде не хранится.
 * В отличие от reference_rows, записи физически удаляются при DELETED_AT.
 */
@Entity(
    tableName = "terminal_keys",
    indices = [Index("VERSION")]
)
data class TerminalKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "KEY_ID")
    val keyId: String,
    @ColumnInfo(name = "KEY_MATERIAL_ENC")
    val keyMaterialEnc: String,
    @ColumnInfo(name = "CREATED_AT")
    val createdAt: Long,
    @ColumnInfo(name = "DELETED_AT")
    val deletedAt: Long = 0,
    @ColumnInfo(name = "VERSION")
    val version: Long = 0
)