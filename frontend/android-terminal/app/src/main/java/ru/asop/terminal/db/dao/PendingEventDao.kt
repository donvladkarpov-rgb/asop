package ru.asop.terminal.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.asop.terminal.db.entity.PendingEventEntity

@Dao
interface PendingEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PendingEventEntity)

    @Query("SELECT * FROM pending_events WHERE status = 'PENDING' ORDER BY created_at ASC")
    suspend fun getPending(): List<PendingEventEntity>

    @Query("SELECT * FROM pending_events WHERE status = 'PENDING' ORDER BY created_at ASC")
    fun observePending(): Flow<List<PendingEventEntity>>

    @Query("UPDATE pending_events SET status = :status, sent_at = :sentAt, error_message = NULL WHERE id = :id")
    suspend fun markSent(id: String, sentAt: Long = System.currentTimeMillis(), status: String = PendingEventEntity.STATUS_SENT)

    @Query("UPDATE pending_events SET status = :status, gateway_event_id = :gatewayEventId, sent_at = :sentAt, error_message = NULL WHERE id = :id")
    suspend fun markSending(id: String, gatewayEventId: String, sentAt: Long = System.currentTimeMillis(), status: String = PendingEventEntity.STATUS_SENDING)

    @Query("UPDATE pending_events SET status = :status, error_message = :error, retry_count = retry_count + 1 WHERE id = :id")
    suspend fun markFailed(id: String, error: String, status: String = PendingEventEntity.STATUS_FAILED)

    @Query("UPDATE pending_events SET retry_count = retry_count + 1 WHERE id = :id")
    suspend fun incrementPollRetry(id: String)

    @Query("SELECT * FROM pending_events WHERE status = 'SENDING' ORDER BY created_at ASC")
    suspend fun getSending(): List<PendingEventEntity>

    @Query("SELECT * FROM pending_events WHERE status = 'SENDING' ORDER BY created_at ASC")
    fun observeSending(): Flow<List<PendingEventEntity>>

    @Query("SELECT COUNT(*) FROM pending_events WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM pending_events WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM pending_events WHERE created_at < :before AND status != 'PENDING'")
    suspend fun deleteOldEvents(before: Long)
}
