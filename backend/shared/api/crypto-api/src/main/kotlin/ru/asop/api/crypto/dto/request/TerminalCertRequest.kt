package ru.asop.api.crypto.dto.request

import jakarta.validation.constraints.NotBlank

data class TerminalCertRequest(
    @field:NotBlank(message = "Terminal serial is required")
    val terminalSerial: String,

    @field:NotBlank(message = "Carrier ID is required")
    val carrierId: String,

    @field:NotBlank(message = "Public key is required")
    val publicKeyBase64: String
)