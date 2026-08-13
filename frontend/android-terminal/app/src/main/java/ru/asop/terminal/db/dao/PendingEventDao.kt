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

    /**
     * Промпт 012: SyncWorker читает pending events в порядке seq ASC (НЕ created_at ASC)
     * чтобы серверный watermark применял их per гарантии порядка.
     */
    @Query("SELECT * FROM pending_events WHERE status = 'PENDING' ORDER BY seq ASC, created_at ASC")
    suspend fun getPending(): List<PendingEventEntity>

    @Query("SELECT * FROM pending_events WHERE status = 'PENDING' ORDER BY seq ASC, created_at ASC")
    fun observePending(): Flow<List<PendingEventEntity>>

    @Query("SELECT COUNT(*) FROM pending_events WHERE status = 'PENDING' AND seq > 0")
    suspend fun countPending(): Int

    @Query("SELECT MAX(seq) FROM pending_events")
    suspend fun maxSeq(): Long?

    @Query("SELECT MIN(seq) FROM pending_events WHERE status = 'PENDING'")
    suspend fun minPendingSeq(): Long?

    @Query("UPDATE pending_events SET status = :status, sent_at = :sentAt, error_message = NULL WHERE id = :id")
    suspend fun markSent(id: String, sentAt: Long = System.currentTimeMillis(), status: String = PendingEventEntity.STATUS_SENT)

    @Query("UPDATE pending_events SET status = :status, gateway_event_id = :gatewayEventId, sent_at = :sentAt, error_message = NULL WHERE id = :id")
    suspend fun markSending(id: String, gatewayEventId: String, sentAt: Long = System.currentTimeMillis(), status: String = PendingEventEntity.STATUS_SENDING)

    @Query("UPDATE pending_events SET status = :status, error_message = :error, retry_count = retry_count + 1 WHERE id = :id")
    suspend fun markFailed(id: String, error: String, status: String = PendingEventEntity.STATUS_FAILED)

    @Query("UPDATE pending_events SET retry_count = retry_count + 1 WHERE id = :id")
    suspend fun incrementPollRetry(id: String)

    @Query("SELECT * FROM pending_events WHERE status = 'SENDING' ORDER BY seq ASC")
    suspend fun getSending(): List<PendingEventEntity>

    @Query("SELECT * FROM pending_events WHERE status = 'SENDING' ORDER BY seq ASC")
    fun observeSending(): Flow<List<PendingEventEntity>>

    @Query("SELECT COUNT(*) FROM pending_events WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM pending_events WHERE created_at < :before AND status != 'PENDING'")
    suspend fun deleteOldEvents(before: Long)
}
