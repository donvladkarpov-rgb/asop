package ru.asop.terminal.network.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Запрос дельта-синхронизации. lastUpdatedAt: таблица → ISO-8601.
 */
@JsonClass(generateAdapter = true)
data class DeltaSyncRequest(
    @Json(name = "terminalId") val terminalId: String,
    @Json(name = "lastUpdatedAt") val lastUpdatedAt: Map<String, String> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class FullSyncRequest(
    @Json(name = "terminalId") val terminalId: String
)

@JsonClass(generateAdapter = true)
data class DeltaMetaResponse(
    @Json(name = "totalChunks") val totalChunks: Int? = null,
    @Json(name = "totalBytes") val totalBytes: Long? = null,
    @Json(name = "createdAt") val createdAt: String? = null,
    @Json(name = "s3Url") val s3Url: String? = null
)
