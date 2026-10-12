package ru.asop.distributor.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import ru.asop.distributor.network.models.AcceptedResponse
import ru.asop.distributor.network.models.AsopKeyRow
import ru.asop.distributor.network.models.DistributorFiscalizerRow
import ru.asop.distributor.network.models.DistributorRegisterRequest
import ru.asop.distributor.network.models.DistributorTerminalResponse
import ru.asop.distributor.network.models.FiscalReceiptRequest
import ru.asop.distributor.network.models.FiscalizerRow
import ru.asop.distributor.network.models.TariffRateRow

/**
 * mTLS контур дистрибьютора (gateway DistributorSyncController, /api/v1/sync/distributor - путь ниже).
 * Прямой pull JSON-/delta глобальных справочников (asop_keys + asop_tariff_rates + фискализаторы)
 * без orchestrator/proto — тот же keyset (versionSince), что использует оркестратор.
 */
interface DistributorSyncApi {

    /** Идемпотентный онбординг ASOP_DISTRIBUTOR_TERMINALS (upsert по terminalSerial). */
    @POST("api/v1/sync/distributor/register")
    suspend fun register(@Body request: DistributorRegisterRequest): Response<DistributorTerminalResponse>

    @GET("api/v1/sync/distributor/keys/delta")
    suspend fun keysDelta(
        @Query("versionSince") versionSince: Long,
        @Query("includeDeleted") includeDeleted: Boolean = true,
        @Query("limit") limit: Int = 10000
    ): Response<List<AsopKeyRow>>

    @GET("api/v1/sync/distributor/tariffs/delta")
    suspend fun tariffsDelta(
        @Query("versionSince") versionSince: Long,
        @Query("includeDeleted") includeDeleted: Boolean = true,
        @Query("limit") limit: Int = 10000
    ): Response<List<TariffRateRow>>

    /** Глобальный словарь фискализаторов (промпт 017). */
    @GET("api/v1/sync/distributor/fiscalizers/delta")
    suspend fun fiscalizersDelta(
        @Query("versionSince") versionSince: Long,
        @Query("includeDeleted") includeDeleted: Boolean = true,
        @Query("limit") limit: Int = 10000
    ): Response<List<FiscalizerRow>>

    /** Настройки фискализации дистрибьютора (фильтр по cardsDistributorId). */
    @GET("api/v1/sync/distributor/distributor-fiscalizers/delta")
    suspend fun distributorFiscalizersDelta(
        @Query("versionSince") versionSince: Long,
        @Query("cardsDistributorId") cardsDistributorId: String,
        @Query("includeDeleted") includeDeleted: Boolean = true,
        @Query("limit") limit: Int = 10000
    ): Response<List<DistributorFiscalizerRow>>

    /** Запрос фискального чека (source=DISTRIBUTOR) — общий с терминалом mTLS endpoint. */
    @POST("api/v1/sync/fiscal/receipts")
    suspend fun requestFiscalReceipt(@Body request: FiscalReceiptRequest): Response<AcceptedResponse>
}