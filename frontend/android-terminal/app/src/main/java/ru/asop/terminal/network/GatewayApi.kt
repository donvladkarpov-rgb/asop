package ru.asop.terminal.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import ru.asop.terminal.network.models.*

interface GatewayApi {

    @POST("api/v1/terminals/register")
    suspend fun registerTerminal(@Body request: TerminalRegisterRequest): TerminalRegisterResponse

    @GET("api/v1/terminals/{id}")
    suspend fun getTerminal(@Path("id") id: String): TerminalResponse

    @PUT("api/v1/terminals/{id}/status")
    suspend fun changeTerminalStatus(
        @Path("id") id: String,
        @Body request: TerminalStatusChangeRequest
    ): TerminalResponse

    @GET("api/v1/events/{eventId}")
    suspend fun getEventStatus(@Path("eventId") eventId: String): Response<EventStatusResponse>

    @PUT("api/v1/terminals/{id}/carrier")
    suspend fun assignCarrier(
        @Path("id") id: String,
        @Body request: TerminalCarrierAssignRequest
    ): TerminalResponse

    @GET("api/v1/regions")
    suspend fun listRegions(): List<RegionResponse>

    @GET("api/v1/carriers")
    suspend fun listCarriers(@Query("regionId") regionId: String? = null): List<CarrierResponse>
}
