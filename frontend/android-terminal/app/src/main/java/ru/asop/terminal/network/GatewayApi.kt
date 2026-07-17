package ru.asop.terminal.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import ru.asop.terminal.network.models.*

interface GatewayApi {

    @POST("api/v1/terminals/register")
    suspend fun registerTerminal(@Body request: TerminalRegisterRequest): TerminalResponse

    @GET("api/v1/terminals/{id}")
    suspend fun getTerminal(@Path("id") id: String): TerminalResponse

    @PUT("api/v1/terminals/{id}/status")
    suspend fun changeTerminalStatus(
        @Path("id") id: String,
        @Body request: TerminalStatusChangeRequest
    ): TerminalResponse

    @GET("api/v1/events/{eventId}")
    suspend fun getEventStatus(@Path("eventId") eventId: String): Response<EventStatusResponse>
}
