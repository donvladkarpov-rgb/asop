package ru.asop.api.crypto.dto.request

import jakarta.validation.constraints.NotBlank

data class ServerCertRequest(
    @field:NotBlank(message = "Common name is required")
    val commonName: String,

    @field:NotBlank(message = "Public key is required")
    val publicKeyBase64: String,

    val dnsNames: List<String> = emptyList()
)
