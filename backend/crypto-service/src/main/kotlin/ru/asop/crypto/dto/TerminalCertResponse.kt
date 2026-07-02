package ru.asop.crypto.dto

import java.time.Instant

data class TerminalCertResponse(
    val certificateBase64: String,
    val serialNumber: String,
    val validFrom: Instant,
    val validUntil: Instant
)