package ru.asop.api.terminal.dto.response

import java.util.UUID

data class TerminalRegisterResponse(
    val terminal: TerminalResponse,
    val operationStatus: String,
    val errorMessage: String? = null
)
