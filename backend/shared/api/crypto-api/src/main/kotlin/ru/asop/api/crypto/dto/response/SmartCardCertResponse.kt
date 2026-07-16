package ru.asop.api.crypto.dto.response

import java.time.Instant

data class SmartCardCertResponse(
    val certificateBase64: String,
    val serialNumber: String,
    val subjectDn: String,
    val validFrom: Instant,
    val validUntil: Instant
)