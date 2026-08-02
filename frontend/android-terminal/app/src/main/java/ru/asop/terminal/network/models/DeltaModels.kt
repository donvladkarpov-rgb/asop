package ru.asop.terminal.network.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Запрос дельта-синхронизации. lastVersion: глобальный VERSION-курсор (sequence).
 */
@JsonClass(generateAdapter = true)
data class DeltaSyncRequest(
    @Json(name = "terminalId") val terminalId: String,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "lastVersion") val lastVersion: Long? = null
)

@JsonClass(generateAdapter = true)
data class FullSyncRequest(
    @Json(name = "terminalId") val terminalId: String,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "regionId") val regionId: String? = null
)

@JsonClass(generateAdapter = true)
data class DeltaMetaResponse(
    @Json(name = "totalChunks") val totalChunks: Int? = null,
    @Json(name = "totalBytes") val totalBytes: Long? = null,
    @Json(name = "createdAt") val createdAt: String? = null,
    @Json(name = "s3Url") val s3Url: String? = null
)
