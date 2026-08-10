package ru.asop.terminal.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import ru.asop.terminal.network.models.*

interface SyncApi {

    @POST("api/v1/sync/sessions/open")
    suspend fun openSession(@Body request: SessionOpenRequest): Response<AcceptedResponse>

    @PUT("api/v1/sync/sessions/{id}/close")
    suspend fun closeSession(
        @Path("id") id: String,
        @Body request: SessionCloseRequest
    ): Response<AcceptedResponse>

    @POST("api/v1/sync/transactions")
    suspend fun completeTransaction(
        @Body request: TransactionCompleteRequest
    ): Response<AcceptedResponse>

    @POST("api/v1/sync/cards/register")
    suspend fun registerCard(@Body request: CardRegisterRequest): Response<AcceptedResponse>

    @POST("api/v1/sync/cards/{id}/block")
    suspend fun blockCard(
        @Path("id") id: String,
        @Body request: CardBlockRequest
    ): Response<AcceptedResponse>

    @POST("api/v1/sync/debts")
    suspend fun createDebt(@Body request: DebtCreateRequest): Response<AcceptedResponse>

    @PUT("api/v1/sync/debts/{id}/recover")
    suspend fun recoverDebt(
        @Path("id") id: String,
        @Body request: DebtRecoverRequest
    ): Response<AcceptedResponse>

    @POST("api/v1/sync/fiscal/receipts")
    suspend fun requestFiscalReceipt(
        @Body request: FiscalReceiptRequest
    ): Response<AcceptedResponse>

    @POST("api/v1/sync/audit/tasks")
    suspend fun createAuditTask(
        @Body request: AuditTaskCreateRequest
    ): Response<AcceptedResponse>

    @POST("api/v1/sync/gps/positions")
    suspend fun reportGpsPosition(
        @Body request: GpsPositionReport
    ): Response<AcceptedResponse>

    @POST("api/v1/sync/smart-cards/sign")
    suspend fun signCardIdentity(
        @Body request: CardIdentitySignRequest
    ): Response<CardIdentitySignResponse>

    @POST("api/v1/sync/cards/activate")
    suspend fun activateCard(
        @Body request: CardActivateRequest
    ): Response<CardActivateResponse>

    @GET("api/v1/sync/cards/by-uid/{uid}")
    suspend fun getCardByUid(@Path("uid") uid: String): Response<CardByUidResponse>

    @POST("api/v1/sync/auth/root")
    suspend fun rootLogin(
        @Body request: RootLoginRequest
    ): Response<RootLoginResponse>
}
