package ru.asop.terminal.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import ru.asop.terminal.network.models.CertSignRequest
import ru.asop.terminal.network.models.EventStatusResponse

/**
 * API для запроса нового X.509 сертификата терминала.
 *
 * Использует plain (без mTLS) HTTPS-клиент, потому что на момент первого
 * запроса у терминала ещё нет сертификата — chicken-and-egg.
 *
 * Поток:
 *   1. POST /api/v1/terminals/cert-sign → 202 + X-Event-Id
 *   2. Polling GET /api/v1/events/{eventId} → 202 (PENDING) / 200 (COMPLETED) / 422 (FAILED)
 */
interface CertSignApi {

    @POST("api/v1/terminals/cert-sign")
    suspend fun requestCertSign(@Body request: CertSignRequest): Response<EventStatusResponse>

    @GET("api/v1/events/{eventId}")
    suspend fun getEventStatus(
        @Header("X-Event-Id") eventIdHeader: String,
        @Path("eventId") eventId: String
    ): Response<EventStatusResponse>
}