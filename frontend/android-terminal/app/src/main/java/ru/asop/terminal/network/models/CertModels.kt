package ru.asop.terminal.network.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CertSignRequest(
    @Json(name = "terminalSerial") val terminalSerial: String,
    @Json(name = "terminalNumber") val terminalNumber: String? = null,
    @Json(name = "terminalModel") val terminalModel: String? = null,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "terminalId") val terminalId: String? = null,
    @Json(name = "publicKeyBase64") val publicKeyBase64: String
)

@JsonClass(generateAdapter = true)
data class EventStatusResponse(
    @Json(name = "eventId") val eventId: String,
    @Json(name = "commandTopic") val commandTopic: String,
    @Json(name = "state") val state: String,
    @Json(name = "resultData") val resultData: String? = null,
    @Json(name = "errorMessage") val errorMessage: String? = null,
    @Json(name = "createdAt") val createdAt: String,
    @Json(name = "completedAt") val completedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CertStoredResult(
    @Json(name = "certId") val certId: String,
    @Json(name = "terminalId") val terminalId: String,
    @Json(name = "terminalNumber") val terminalNumber: String?,
    @Json(name = "certSerial") val certSerial: String,
    @Json(name = "certificateBase64") val certificateBase64: String,
    @Json(name = "validFrom") val validFrom: String,
    @Json(name = "validUntil") val validUntil: String,
    @Json(name = "caChain") val caChain: String
)