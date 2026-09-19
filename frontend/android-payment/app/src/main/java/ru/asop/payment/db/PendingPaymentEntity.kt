package ru.asop.payment.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Кэш локальных результатов /pay (идемпотентность по request_id) + офлайн-очередь
 * отчётов `POST /api/v1/payment/report` (report_status PENDING → SENT).
 */
@Entity(tableName = "pending_payments")
data class PendingPaymentEntity(
    @PrimaryKey val requestId: String,
    val paymentId: String,
    val payloadJson: String,
    val paymentType: String = "FARE",
    val reportStatus: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)