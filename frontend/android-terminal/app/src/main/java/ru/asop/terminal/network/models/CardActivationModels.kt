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

@JsonClass(generateAdapter = true)
data class CardActivateRequest(
    @Json(name = "cardIdentity") val cardIdentity: CardIdentity,
    @Json(name = "identityJson") val identityJson: String,
    @Json(name = "identitySignature") val identitySignature: String,
    @Json(name = "operatorRoles") val operatorRoles: List<String> = emptyList(),
    @Json(name = "authorizedByRoot") val authorizedByRoot: Boolean = false,
    @Json(name = "rootUserId") val rootUserId: String? = null
)

@JsonClass(generateAdapter = true)
data class CardActivateResponse(
    @Json(name = "cardId") val cardId: String,
    @Json(name = "cardRole") val cardRole: String,
    @Json(name = "registeredAt") val registeredAt: String
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