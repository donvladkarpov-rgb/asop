package ru.asop.crypto.dto

import jakarta.validation.constraints.NotBlank

data class SmartCardCertRequest(
    @field:NotBlank val cardId: String,
    @field:NotBlank val cardRole: String,      // DRIVER, CONTROLLER, PASSENGER_BENEFIT, etc.
    val carrierId: String?,                     // nullable для админов системы
    @field:NotBlank val publicKeyBase64: String
)

data class SmartCardCertResponse(
    val certificateBase64: String,
    val serialNumber: String,
    val subjectDn: String,
    val validFrom: java.time.Instant,
    val validUntil: java.time.Instant
)