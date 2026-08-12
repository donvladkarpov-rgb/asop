package ru.asop.terminal.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Промпт 011: trip_payments сохраняет каждое прикладывание пассажирской карты
 * внутри открытого TRIP. Асинхронно отправляется на сервер через SyncWorker.
 * MVP: amount=0, transactionResultId='VALIDATION_ONLY'.
 */
@Entity(
    tableName = "trip_payments",
    indices = [
        Index("trip_session_id"),
        Index("card_id"),
        Index("last_sync_at")
    ]
)
data class TripPaymentEntity(
    @PrimaryKey val id: String,                                           // UUIDv7
    @ColumnInfo(name = "trip_session_id") val tripSessionId: String,        // parent_session_id of trip
    @ColumnInfo(name = "card_id") val cardId: String? = null,               // ASOP_CARDS.card_id (может быть null для anon)
    @ColumnInfo(name = "transaction_type_id") val transactionTypeId: String = "00000000-0000-0000-0000-000000000803", // VALIDATION
    @ColumnInfo(name = "transaction_result_id") val transactionResultId: String = "00000000-0000-0000-0000-000000000903", // VALIDATION_ONLY
    val amount: Double = 0.0,
    val currency: String = "RUB",
    val timestamp: Long,
    @ColumnInfo(name = "region_id") val regionId: String? = null,
    @ColumnInfo(name = "carrier_id") val carrierId: String? = null,
    @ColumnInfo(name = "timezone") val timezone: String? = null,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "metadata") val metadata: String? = null
)
