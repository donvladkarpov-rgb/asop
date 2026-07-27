package ru.asop.terminal.network.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TerminalRegisterRequest(
    @Json(name = "terminalSerial") val terminalSerial: String,
    @Json(name = "terminalNumber") val terminalNumber: String? = null,
    @Json(name = "terminalModel") val terminalModel: String? = null,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "terminalId") val terminalId: String? = null
)

@JsonClass(generateAdapter = true)
data class TerminalStatusChangeRequest(
    @Json(name = "newStatus") val newStatus: String,
    @Json(name = "reason") val reason: String? = null
)

@JsonClass(generateAdapter = true)
data class TerminalRegisterResponse(
    @Json(name = "terminal") val terminal: TerminalResponse,
    @Json(name = "operationStatus") val operationStatus: String,
    @Json(name = "errorMessage") val errorMessage: String? = null
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
