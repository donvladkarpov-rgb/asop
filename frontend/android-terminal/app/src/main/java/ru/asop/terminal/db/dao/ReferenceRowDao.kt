package ru.asop.terminal.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import ru.asop.terminal.db.entity.ReferenceRowEntity

@Dao
interface ReferenceRowDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ReferenceRowEntity>)

    @Transaction
    suspend fun applyBatch(rows: List<ReferenceRowEntity>) {
        if (rows.isEmpty()) return
        upsertAll(rows)
    }

    @Query("SELECT * FROM reference_rows WHERE table_name = :tableName AND deleted_at IS NULL ORDER BY updated_at")
    fun observeTable(tableName: String): Flow<List<ReferenceRowEntity>>

    @Query("SELECT * FROM reference_rows WHERE table_name = :tableName AND deleted_at IS NULL")
    suspend fun getActiveByTable(tableName: String): List<ReferenceRowEntity>

    @Query("SELECT COUNT(*) FROM reference_rows WHERE deleted_at IS NULL")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT MAX(version) FROM reference_rows")
    suspend fun maxVersion(): Long?

    @Query("DELETE FROM reference_rows WHERE table_name = :tableName")
    suspend fun deleteByTable(tableName: String)

    /**
     * Промпт 011: lookup carrier_id для userId через таблицу user_carriers
     * (joined into asop_user_carriers delta + applyChunk — payload JSON содержит {"userId":"...","carrierId":"..."}).
     */
    @Query("SELECT payload_json FROM reference_rows WHERE table_name = 'asop_user_carriers' AND deleted_at IS NULL AND payload_json LIKE '%' || :userId || '%' LIMIT 1")
    suspend fun findUserCarrierRowRaw(userId: String): String?

    /**
     * SELECTs first carrier_id for given user from asop_user_carriers rows.
     */
    @Query("""
        SELECT payload_json
        FROM reference_rows
        WHERE table_name = 'asop_user_carriers'
          AND deleted_at IS NULL
          AND payload_json LIKE '%' || :userId || '%'
        LIMIT 1
    """)
    suspend fun rawUserCarriersFor(userId: String): String?

/**
     * Full name for user via asop_users row.
     */
    @Query("""
        SELECT payload_json
        FROM reference_rows
        WHERE table_name = 'asop_users'
          AND row_id = :userId
          AND deleted_at IS NULL
          LIMIT 1
    """)
    suspend fun rawUserByIdRow(userId: String): String?

    /**
     * Промпт 011: первый привязанный carrier для user (lookup через asop_user_carriers payload).
     */
    @Query("""
        SELECT payload_json
        FROM reference_rows
        WHERE table_name = 'asop_user_carriers'
          AND deleted_at IS NULL
          AND row_id LIKE :userId || '|%'
        LIMIT 1
    """)
    suspend fun firstUserCarrierRow(userId: String): String?
}
