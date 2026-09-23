package ru.asop.distributor.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import ru.asop.distributor.network.models.AsopKeyRow
import ru.asop.distributor.network.models.DistributorRegisterRequest
import ru.asop.distributor.network.models.DistributorTerminalResponse
import ru.asop.distributor.network.models.TariffRateRow

/**
 * mTLS контур дистрибьютора (gateway DistributorSyncController, /api/v1/sync/distributor - путь ниже).
 * Прямой pull JSON-/delta глобальных справочников (asop_keys + asop_tariff_rates)
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
}