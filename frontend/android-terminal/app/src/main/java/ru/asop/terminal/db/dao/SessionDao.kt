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

    /**
     * Промпт 011: текущая открытая смена SHIFT (root-level session).
     */
    @Query("SELECT * FROM sessions WHERE session_type_code = 'SHIFT' AND status = 'OPEN' ORDER BY opened_at DESC LIMIT 1")
    suspend fun getCurrentOpenShift(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE session_type_code = 'SHIFT' AND status = 'OPEN' ORDER BY opened_at DESC LIMIT 1")
    fun observeCurrentOpenShift(): Flow<SessionEntity?>

    /**
     * Промпт 011: текущий открытый TRIP (child of shift).
     */
    @Query("""
        SELECT * FROM sessions
        WHERE session_type_code = 'TRIP' AND status = 'OPEN' AND parent_session_id = :parentId
        ORDER BY opened_at DESC LIMIT 1
    """)
    suspend fun getCurrentOpenTrip(parentId: String): SessionEntity?

    @Query("""
        SELECT * FROM sessions
        WHERE session_type_code = 'TRIP' AND status = 'OPEN'
        ORDER BY opened_at DESC LIMIT 1
    """)
    fun observeCurrentOpenTrip(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE status = 'OPEN' ORDER BY opened_at DESC")
    fun observeAllOpen(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY opened_at DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /**
     * Промпт 011: idempotent close — клиент генерирует UUIDv7 sessionId,
     * server INSERT … ON CONFLICT (SESSION_ID) DO NOTHING → нет дублей.
     */
    @Query("UPDATE sessions SET status = :status, closed_at = :closedAt, closed_at_local = :closedAt, closed_by_user_id = :closedByUserId WHERE id = :id AND status <> 'CLOSED'")
    suspend fun close(id: String, closedAt: Long = System.currentTimeMillis(), closedByUserId: String? = null, status: String = SessionEntity.STATUS_CLOSED)

    @Query("UPDATE sessions SET last_sync_at = :syncAt WHERE id = :id")
    suspend fun markSynced(id: String, syncAt: Long = System.currentTimeMillis())
}
