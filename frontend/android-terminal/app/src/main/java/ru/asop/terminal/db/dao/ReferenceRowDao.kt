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

    /**
     * Промпт 011: TID-пулы для перевозчика. payload JSON содержит
     * `{"tidId":"...","carrierId":"...","tidValue":"...","status":"UNUSED"}`.
     * Сортировка на клиенте по payloadJson.tidValue (Room не знает schema payload_json).
     */
    @Query("""
        SELECT payload_json
        FROM reference_rows
        WHERE table_name = 'asop_tids'
          AND deleted_at IS NULL
          AND payload_json LIKE '%"carrierId": "' || :carrierId || '"%'
    """)
    fun observeTidsByCarrier(carrierId: String): Flow<List<String>>

    /**
     * Промпт 011: транспортные средства перевозчика. payload содержит
     * `{"vehicleId":"...","carrierId":"...","modelId":"...","registrationNumber":"..."}`.
     * Сортировка по registrationNumber делается на клиенте.
     */
    @Query("""
        SELECT payload_json
        FROM reference_rows
        WHERE table_name = 'asop_vehicles'
          AND deleted_at IS NULL
          AND payload_json LIKE '%"carrierId": "' || :carrierId || '"%'
    """)
    fun observeVehiclesByCarrier(carrierId: String): Flow<List<String>>

    /**
     * Промпт 011: все маршруты (роутинг по гео-зоне — на сервере, тут просто список).
     * Сортировка по routeNumber делается на клиенте.
     */
    @Query("""
        SELECT payload_json
        FROM reference_rows
        WHERE table_name = 'asop_routes'
          AND deleted_at IS NULL
    """)
    fun observeAllRoutes(): Flow<List<String>>

    /**
     * Промпт 011: пути следования маршрута. payload содержит `{pathId, routeId, pathName, ...}`.
     * Сортировка по pathName делается на клиенте.
     */
    @Query("""
        SELECT payload_json
        FROM reference_rows
        WHERE table_name = 'asop_paths'
          AND deleted_at IS NULL
          AND payload_json LIKE '%"routeId": "' || :routeId || '"%'
    """)
    fun observePathsByRoute(routeId: String): Flow<List<String>>

    // ===== Промпт 014: поиск льготы пассажира (cardId → user → benefit) =====

    /** userId из asop_cards по cardId. payload: {"cardId":..., "userId":...}. */
    @Query("""
        SELECT payload_json
        FROM reference_rows
        WHERE table_name = 'asop_cards'
          AND deleted_at IS NULL
          AND payload_json LIKE '%"cardId": "' || :cardId || '"%'
        LIMIT 1
    """)
    suspend fun findUserIdByCardId(cardId: String): String?

    /** Активные назначения льгот пользователя (del нулевые) — список payload_json. */
    @Query("""
        SELECT payload_json
        FROM reference_rows
        WHERE table_name = 'asop_user_benefits'
          AND deleted_at IS NULL
          AND payload_json LIKE '%"userId": "' || :userId || '"%'
    """)
    suspend fun findUserBenefits(userId: String): List<String>
}
