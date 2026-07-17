package ru.asop.terminal.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.asop.terminal.db.entity.TransactionEntity

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE session_id = :sessionId ORDER BY created_at DESC")
    suspend fun getBySession(sessionId: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE session_id = :sessionId ORDER BY created_at DESC")
    fun observeBySession(sessionId: String): Flow<List<TransactionEntity>>

    @Query("UPDATE transactions SET status = :status, synced_at = :syncedAt WHERE id = :id")
    suspend fun markSynced(id: String, syncedAt: Long = System.currentTimeMillis(), status: String = TransactionEntity.STATUS_COMPLETED)

    @Query("UPDATE transactions SET status = :status WHERE id = :id")
    suspend fun markFailed(id: String, status: String = TransactionEntity.STATUS_FAILED)

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'PENDING' AND session_id = :sessionId")
    suspend fun getUnsyncedCount(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'PENDING' AND session_id = :sessionId")
    fun observeUnsyncedCount(sessionId: String): Flow<Int>
}
