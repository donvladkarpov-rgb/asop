package ru.asop.api.terminal.dto.response

import java.time.Instant
import java.util.UUID

data class TerminalResponse(
    val id: UUID,
    val terminalSerial: String,
    val terminalNumber: String?,
    val terminalModel: String?,
    val carrierId: UUID?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
