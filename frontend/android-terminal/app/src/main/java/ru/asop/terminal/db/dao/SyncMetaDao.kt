package ru.asop.terminal.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.asop.terminal.db.entity.SyncMetaEntity

@Dao
interface SyncMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: SyncMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SyncMetaEntity>)

    @Query("SELECT * FROM sync_meta")
    suspend fun getAll(): List<SyncMetaEntity>

    @Query("SELECT * FROM sync_meta")
    fun observeAll(): Flow<List<SyncMetaEntity>>
}
