package ru.asop.api.terminal.dto.request

import java.util.UUID

data class TerminalCarrierAssignRequest(
    val carrierId: UUID? = null
)
