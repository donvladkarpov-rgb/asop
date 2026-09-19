package ru.asop.payment.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingPaymentDao {

    /** Идемпотентность /pay: при повторном запросе с тем же requestId. */
    @Query("SELECT payloadJson FROM pending_payments WHERE requestId = :requestId LIMIT 1")
    suspend fun findByIdempotentKey(requestId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PendingPaymentEntity)

    @Query("SELECT * FROM pending_payments WHERE reportStatus = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingReports(): List<PendingPaymentEntity>

    @Query("UPDATE pending_payments SET reportStatus = :status WHERE paymentId = :paymentId")
    suspend fun markReported(paymentId: String, status: String)
}