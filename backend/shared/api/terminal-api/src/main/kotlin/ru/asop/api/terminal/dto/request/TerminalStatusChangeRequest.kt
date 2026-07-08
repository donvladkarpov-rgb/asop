package ru.asop.api.terminal.dto.request

import jakarta.validation.constraints.NotBlank

data class TerminalStatusChangeRequest(
    @field:NotBlank(message = "New status is required")
    val newStatus: String,

    val reason: String? = null
)
