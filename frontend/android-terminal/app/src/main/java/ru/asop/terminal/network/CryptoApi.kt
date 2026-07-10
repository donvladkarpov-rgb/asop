package ru.asop.terminal.network

import retrofit2.http.Body
import retrofit2.http.POST
import ru.asop.terminal.network.models.*

interface CryptoApi {

    @POST("api/v1/terminals/register")
    suspend fun requestCertificate(@Body request: TerminalCertRequest): TerminalCertResponse
}
