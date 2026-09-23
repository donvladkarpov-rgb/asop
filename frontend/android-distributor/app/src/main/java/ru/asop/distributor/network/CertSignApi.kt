package ru.asop.distributor.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import ru.asop.distributor.network.models.AcceptedResponse
import ru.asop.distributor.network.models.CertSignRequest
import ru.asop.distributor.network.models.EventStatusResponse

/**
 * Cert-sign дистрибьютора (plain HTTPS, без mTLS — до первого сертификата chicken-and-egg):
 *   POST /api/v1/distributor-terminals/cert-sign → 202 + X-Event-Id (сага сохраняет ASOP_DISTRIBUTOR_TERMINALS)
 *   GET  /api/v1/events/{eventId}                → 202 PENDING / 200 COMPLETED / 422 FAILED
 */
interface CertSignApi {

    @POST("api/v1/distributor-terminals/cert-sign")
    suspend fun requestCertSign(@Body request: CertSignRequest): Response<AcceptedResponse>

    @GET("api/v1/events/{eventId}")
    suspend fun getEventStatus(
        @Header("X-Event-Id") eventIdHeader: String,
        @Path("eventId") eventId: String
    ): Response<EventStatusResponse>
}