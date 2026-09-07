package ru.asop.api.terminal.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class TerminalRegisterRequest(
    @field:NotBlank(message = "Terminal serial is required")
    @field:Size(max = 64, message = "Terminal serial must be less than 64 characters")
    val terminalSerial: String,

    @field:Size(max = 16, message = "Terminal number must be less than 16 characters")
    val terminalNumber: String? = null,

    @field:Size(max = 100, message = "Terminal model must be less than 100 characters")
    val terminalModel: String? = null,

    val carrierId: UUID? = null,

    val regionId: UUID? = null,

    val timezone: String? = null,

    val terminalId: UUID? = null,

    val profileId: UUID? = null
)
