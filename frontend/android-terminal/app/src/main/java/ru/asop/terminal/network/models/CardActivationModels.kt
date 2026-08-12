package ru.asop.terminal.network.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CardIdentitySignRequest(
    @Json(name = "identityJson") val identityJson: String
)

@JsonClass(generateAdapter = true)
data class CardIdentitySignResponse(
    @Json(name = "signatureBase64") val signatureBase64: String
)

@JsonClass(generateAdapter = true)
data class CardIdentity(
    @Json(name = "cardId") val cardId: String,
    @Json(name = "uid") val uid: String,
    @Json(name = "regionId") val regionId: String? = null,
    @Json(name = "organizerId") val organizerId: String? = null,
    @Json(name = "carrierId") val carrierId: String? = null,
    @Json(name = "cardsDistributorId") val cardsDistributorId: String? = null,
    @Json(name = "auditServiceId") val auditServiceId: String? = null,
    @Json(name = "userId") val userId: String? = null,
    @Json(name = "roles") val roles: List<String>
)

/**
 * Промпт 008: VCM1-активация для MIFARE Classic карт (без RSA-PSS подписи).
 * Терминал шлёт client-generated cardId (UUID v7 по UTC) — сервер возвращает свой masterCardId
 * (UID-based) если карта уже была зарегистрирована.
 */
@JsonClass(generateAdapter = true)
data class CardActivateClassicRequest(
    @Json(name = "technology") val technology: String, // "CLASSIC"
    @Json(name = "cardId") val cardId: String,         // UUID v7 placeholder
    @Json(name = "uid") val uid: String,               // MIFARE UID hex
    @Json(name = "bitmask") val bitmask: Int,           // UInt16, primary role encoded
    @Json(name = "entityType") val entityType: String, // "userId"|"regionId"|...
    @Json(name = "entityId") val entityId: String? = null  // null для PASSENGER_ANONYMOUS
)

@JsonClass(generateAdapter = true)
data class ClassicCardActivateData(
    @Json(name = "cardId") val cardId: String,
    @Json(name = "cardIdOverridden") val cardIdOverridden: Boolean,
    @Json(name = "bitmask") val bitmask: Int,
    @Json(name = "entityType") val entityType: String?,
    @Json(name = "entityId") val entityId: String?,
    @Json(name = "registeredAt") val registeredAt: String
)

@JsonClass(generateAdapter = true)
data class CardActivateRequest(
    /**
     * Обязателен для DESFire-flow. Для VCM1-flow (`vcm1 != null`) допустим null.
     */
    @Json(name = "cardIdentity") val cardIdentity: CardIdentity? = null,
    /**
     * Обязателен для DESFire-flow. Для VCM1-flow допустим null.
     */
    @Json(name = "identityJson") val identityJson: String? = null,
    /**
     * Обязателен для DESFire-flow (RSA-PSS-SHA256, base64). Для VCM1-flow допустим null.
     */
    @Json(name = "identitySignature") val identitySignature: String? = null,
    @Json(name = "operatorRoles") val operatorRoles: List<String> = emptyList(),
    @Json(name = "authorizedByRoot") val authorizedByRoot: Boolean = false,
    @Json(name = "rootUserId") val rootUserId: String? = null,
    /**
     * Технология NFC-карты (промпт 007):
     *  - "DESFIRE" (по умолчанию) — DESFire EV1/EV2/EV3;
     *  - "CLASSIC" — MIFARE Classic 1K/4K.
     * Сервер пишет в ASOP_CARD_MIFARES.CARD_TECH.
     */
    @Json(name = "technology") val technology: String? = null,
    /**
     * VCM1-payload для Classic-карт (промпт 008). Если null — DESFire-flow.
     */
    @Json(name = "vcm1") val vcm1: CardActivateClassicRequest? = null
)

@JsonClass(generateAdapter = true)
data class CardActivateResponse(
    @Json(name = "cardId") val cardId: String,
    @Json(name = "cardRole") val cardRole: String,
    @Json(name = "registeredAt") val registeredAt: String,
    @Json(name = "vcm1") val vcm1: ClassicCardActivateData? = null
)

@JsonClass(generateAdapter = true)
data class RootLoginRequest(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class RootLoginResponse(
    @Json(name = "userId") val userId: String
)

@JsonClass(generateAdapter = true)
data class CardByUidResponse(
    @Json(name = "cardId") val cardId: String,
    @Json(name = "uid") val uid: String
)