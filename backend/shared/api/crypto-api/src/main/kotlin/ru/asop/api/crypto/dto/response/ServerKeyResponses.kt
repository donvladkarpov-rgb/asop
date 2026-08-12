package ru.asop.api.crypto.dto.response

data class ServerKeyPublicResponse(
    val algorithm: String,
    val format: String,
    val publicKeyBase64: String
)

data class DecryptResponse(
    val keyMaterialBase64: String
)

data class GenerateKeyResponse(
    val keyId: String,
    val cipherBase64: String
)