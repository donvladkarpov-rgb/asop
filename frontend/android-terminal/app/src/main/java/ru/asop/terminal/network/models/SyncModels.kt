package ru.asop.terminal.network.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PublicKeyResponse(
    @Json(name = "algorithm") val algorithm: String,
    @Json(name = "format") val format: String,
    @Json(name = "publicKeyBase64") val publicKeyBase64: String
)

@JsonClass(generateAdapter = true)
data class AcceptedResponse(
    @Json(name = "eventId") val eventId: String,
    @Json(name = "topic") val topic: String,
    @Json(name = "acceptedAt") val acceptedAt: String,
    @Json(name = "locationHint") val locationHint: String? = null
)

@JsonClass(generateAdapter = true)
data class SessionOpenRequest(
    @Json(name = "sessionId") val sessionId: String? = null,        // client UUIDv7 (idempotency, промпт 011 §5)
    @Json(name = "sessionTypeId") val sessionTypeId: String,
    @Json(name = "parentSessionId") val parentSessionId: String? = null,
    @Json(name = "terminalId") val terminalId: String? = null,
    @Json(name = "tidId") val tidId: String? = null,                       // промпт 011
    @Json(name = "pathId") val pathId: String? = null,
    @Json(name = "vehicleId") val vehicleId: String? = null,
    @Json(name = "openedByUserId") val openedByUserId: String? = null,     // промпт 011
    @Json(name = "cardId") val cardId: String? = null,                     // VCM1 (промпт 008) + промпт 011
    @Json(name = "carrierId") val carrierId: String? = null,               // промпт 011
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "attributes") val attributes: String? = null              // JSON: {carry extra context}
)

@JsonClass(generateAdapter = true)
data class SessionCloseRequest(
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "cardId") val cardId: String? = null,              // карта-ключ того, кто закрывает смену
    @Json(name = "closedByUserId") val closedByUserId: String? = null // userId закрывающего (карта-ключ)
)

@JsonClass(generateAdapter = true)
data class TransactionCompleteRequest(
    @Json(name = "sessionId") val sessionId: String,
    @Json(name = "transactionTypeId") val transactionTypeId: String,
    @Json(name = "transactionResultId") val transactionResultId: String,
    @Json(name = "amount") val amount: Double,
    @Json(name = "currency") val currency: String = "RUB",
    @Json(name = "cardId") val cardId: String? = null,
    @Json(name = "metadata") val metadata: String? = null,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "timezone") val timezone: String? = null
)

@JsonClass(generateAdapter = true)
data class CardRegisterRequest(
    @Json(name = "cardTypeId") val cardTypeId: String,
    @Json(name = "userId") val userId: String? = null,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "timezone") val timezone: String? = null
)

@JsonClass(generateAdapter = true)
data class CardBlockRequest(
    @Json(name = "blockType") val blockType: String,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "cardId") val cardId: String? = null  // VCM1 (промпт 008)
)

@JsonClass(generateAdapter = true)
data class DebtCreateRequest(
    @Json(name = "cardId") val cardId: String,
    @Json(name = "carrierId") val carrierId: String,
    @Json(name = "debtAmount") val debtAmount: Double,
    @Json(name = "terminalId") val terminalId: String? = null,
    @Json(name = "sessionId") val sessionId: String? = null,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "timezone") val timezone: String? = null
)

@JsonClass(generateAdapter = true)
data class DebtRecoverRequest(
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "cardId") val cardId: String? = null  // VCM1 (промпт 008)
)

@JsonClass(generateAdapter = true)
data class FiscalReceiptRequest(
    @Json(name = "transactionId") val transactionId: String,
    @Json(name = "amount") val amount: Double,
    @Json(name = "description") val description: String? = null,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "cardId") val cardId: String? = null  // VCM1 (промпт 008)
)

@JsonClass(generateAdapter = true)
data class AuditTaskCreateRequest(
    @Json(name = "taskNumber") val taskNumber: String,
    @Json(name = "organizerId") val organizerId: String? = null,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "cardId") val cardId: String? = null  // VCM1 (промпт 008)
)

@JsonClass(generateAdapter = true)
data class GpsPositionReport(
    @Json(name = "vehicleId") val vehicleId: String,
    @Json(name = "pathId") val pathId: String,
    @Json(name = "sessionId") val sessionId: String? = null,
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double,
    @Json(name = "speedKmh") val speedKmh: Double? = null,
    @Json(name = "recordedAt") val recordedAt: String,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "timezone") val timezone: String? = null,
    @Json(name = "cardId") val cardId: String? = null  // VCM1 (промпт 008)
)
