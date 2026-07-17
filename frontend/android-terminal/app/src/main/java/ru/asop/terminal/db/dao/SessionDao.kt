package ru.asop.terminal.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.asop.terminal.db.entity.SessionEntity

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE status = 'OPEN' LIMIT 1")
    suspend fun getCurrentOpenSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status = 'OPEN' LIMIT 1")
    suspend fun getSessionForGps(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status = 'OPEN' LIMIT 1")
    fun observeCurrentOpenSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY opened_at DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM sessions ORDER BY opened_at DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET status = :status, closed_at = :closedAt WHERE id = :id")
    suspend fun close(id: String, closedAt: Long = System.currentTimeMillis(), status: String = SessionEntity.STATUS_CLOSED)

    @Query("UPDATE sessions SET last_sync_at = :syncAt WHERE id = :id")
    suspend fun markSynced(id: String, syncAt: Long = System.currentTimeMillis())
}
