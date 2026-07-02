package ru.asop.dto.terminal

import java.util.UUID

data class TerminalResponse(
    val id: UUID,
    val terminalSerial: String,
    val terminalNumber: String?,
    val terminalModel: String?,
    val carrierId: UUID?,
    val status: String
)