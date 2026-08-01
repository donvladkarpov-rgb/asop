package ru.asop.terminal.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.asop.terminal.db.entity.DeltaSyncJobEntity

@Dao
interface DeltaSyncJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: DeltaSyncJobEntity)

    @Query("SELECT * FROM delta_sync_jobs WHERE status = 'PENDING'")
    suspend fun getPending(): List<DeltaSyncJobEntity>

    @Query("SELECT * FROM delta_sync_jobs WHERE status = 'PENDING'")
    fun observePending(): Flow<List<DeltaSyncJobEntity>>

    @Query("UPDATE delta_sync_jobs SET status = 'COMPLETED', total_chunks = :totalChunks, completed_at = :completedAt WHERE event_id = :eventId")
    suspend fun markCompleted(eventId: String, totalChunks: Int, completedAt: Long)

    @Query("UPDATE delta_sync_jobs SET status = 'FAILED', error_message = :errorMessage, completed_at = :completedAt WHERE event_id = :eventId")
    suspend fun markFailed(eventId: String, errorMessage: String, completedAt: Long)

    @Query("DELETE FROM delta_sync_jobs WHERE event_id = :eventId")
    suspend fun delete(eventId: String)
}
