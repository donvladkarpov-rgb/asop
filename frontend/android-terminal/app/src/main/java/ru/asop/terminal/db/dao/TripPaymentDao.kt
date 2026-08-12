package ru.asop.terminal.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.asop.terminal.db.entity.TripPaymentEntity

/**
 * Промпт 011: trip_payments DAO — каждое прикладывание пассажирской карты внутри TRIP.
 * SyncWorker забирает PENDING через last_sync_at NULL → emit Kafka event.
 */
@Dao
interface TripPaymentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(payment: TripPaymentEntity)

    @Query("SELECT * FROM trip_payments WHERE trip_session_id = :tripId ORDER BY timestamp ASC")
    fun observeForTrip(tripId: String): Flow<List<TripPaymentEntity>>

    @Query("SELECT * FROM trip_payments WHERE last_sync_at IS NULL ORDER BY timestamp ASC LIMIT 200")
    suspend fun getPendingSync(): List<TripPaymentEntity>

    @Query("UPDATE trip_payments SET last_sync_at = :syncAt WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, syncAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM trip_payments WHERE trip_session_id = :tripId")
    suspend fun countForTrip(tripId: String): Int
}
