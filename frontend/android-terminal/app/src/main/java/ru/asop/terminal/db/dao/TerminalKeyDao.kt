package ru.asop.terminal.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import ru.asop.terminal.db.entity.TerminalKeyEntity

@Dao
interface TerminalKeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<TerminalKeyEntity>)

    @Transaction
    suspend fun applyBatch(rows: List<TerminalKeyEntity>) {
        if (rows.isEmpty()) return
        upsertAll(rows)
        deleteMarked(rows.mapNotNull { r -> if (r.deletedAt > 0) r.keyId else null })
    }

    @Query("DELETE FROM terminal_keys WHERE KEY_ID IN (:keyIds)")
    suspend fun deleteIds(keyIds: List<String>): Int

    @Transaction
    suspend fun deleteMarked(keyIds: List<String>) {
        if (keyIds.isEmpty()) return
        deleteIds(keyIds)
    }

    // Свежие ключи первыми (KEY_ID = UUIDv7, time-ordered)
    @Query("SELECT * FROM terminal_keys WHERE DELETED_AT = 0 ORDER BY KEY_ID DESC")
    fun observeActive(): Flow<List<TerminalKeyEntity>>

    @Query("SELECT * FROM terminal_keys WHERE DELETED_AT = 0 ORDER BY KEY_ID DESC LIMIT :limit")
    suspend fun getActive(limit: Int = 10): List<TerminalKeyEntity>
}