package ru.asop.terminal.network.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TerminalRegisterRequest(
    @Json(name = "terminalSerial") val terminalSerial: String,
    @Json(name = "terminalNumber") val terminalNumber: String? = null,
    @Json(name = "terminalModel") val terminalModel: String? = null,
    @Json(name = "carrierId") val carrierId: String? = null
)

@JsonClass(generateAdapter = true)
data class TerminalStatusChangeRequest(
    @Json(name = "newStatus") val newStatus: String,
    @Json(name = "reason") val reason: String? = null
)

@JsonClass(generateAdapter = true)
data class TerminalResponse(
    @Json(name = "id") val id: String,
    @Json(name = "terminalSerial") val terminalSerial: String,
    @Json(name = "terminalNumber") val terminalNumber: String?,
    @Json(name = "terminalModel") val terminalModel: String?,
    @Json(name = "carrierId") val carrierId: String?,
    @Json(name = "status") val status: String,
    @Json(name = "createdAt") val createdAt: String,
    @Json(name = "updatedAt") val updatedAt: String
)

@JsonClass(generateAdapter = true)
data class TerminalCertRequest(
    @Json(name = "terminalSerial") val terminalSerial: String,
    @Json(name = "carrierId") val carrierId: String,
    @Json(name = "publicKeyBase64") val publicKeyBase64: String
)

@JsonClass(generateAdapter = true)
data class TerminalCertResponse(
    @Json(name = "certificateBase64") val certificateBase64: String,
    @Json(name = "serialNumber") val serialNumber: String,
    @Json(name = "validFrom") val validFrom: String,
    @Json(name = "validUntil") val validUntil: String
)
