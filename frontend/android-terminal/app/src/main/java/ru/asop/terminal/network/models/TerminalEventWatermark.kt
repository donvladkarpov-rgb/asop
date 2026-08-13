package ru.asop.terminal.network.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Промпт 012: ответ GET /api/v1/terminals/{id}/event-watermark.
 */
@JsonClass(generateAdapter = true)
data class TerminalEventWatermark(
    @Json(name = "terminalId") val terminalId: String,
    @Json(name = "lastSeq") val lastSeq: Long,
    @Json(name = "pendingSeqCount") val pendingSeqCount: Long,
    @Json(name = "lastEventAt") val lastEventAt: String? = null
)
