package ru.asop.api.session.dto.request

import jakarta.validation.constraints.NotNull
import java.util.UUID

data class SessionOpenRequest(
    @field:NotNull
    val sessionTypeId: UUID,

    val parentSessionId: UUID? = null,

    val terminalId: UUID? = null,

    val pathId: UUID? = null,

    val vehicleId: UUID? = null
)
