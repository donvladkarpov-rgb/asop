package ru.asop.terminal.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import ru.asop.terminal.db.entity.ReferenceRowEntity

@Dao
interface ReferenceRowDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ReferenceRowEntity>)

    @Transaction
    suspend fun applyBatch(rows: List<ReferenceRowEntity>) {
        if (rows.isEmpty()) return
        upsertAll(rows)
    }

    @Query("SELECT * FROM reference_rows WHERE table_name = :tableName AND deleted_at IS NULL ORDER BY updated_at")
    fun observeTable(tableName: String): Flow<List<ReferenceRowEntity>>

    @Query("SELECT * FROM reference_rows WHERE table_name = :tableName AND deleted_at IS NULL")
    suspend fun getActiveByTable(tableName: String): List<ReferenceRowEntity>

    @Query("SELECT COUNT(*) FROM reference_rows WHERE deleted_at IS NULL")
    fun observeActiveCount(): Flow<Int>

    @Query("DELETE FROM reference_rows WHERE table_name = :tableName")
    suspend fun deleteByTable(tableName: String)
}
