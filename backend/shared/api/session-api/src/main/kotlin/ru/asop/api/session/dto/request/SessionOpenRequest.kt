package ru.asop.api.session.dto.request

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.NotNull
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionOpenRequest(
    /** Client-generated UUIDv7 (идемпотентность при offline retry). null → server generates. */
    val sessionId: UUID? = null,

    @field:NotNull
    val sessionTypeId: UUID,

    val parentSessionId: UUID? = null,

    val terminalId: UUID? = null,

    val tidId: UUID? = null,

    val pathId: UUID? = null,

    val vehicleId: UUID? = null,

    val openedByUserId: UUID? = null,

    val cardId: UUID? = null,

    val carrierId: UUID? = null,

    val regionId: UUID? = null,

    val timezone: String? = null,

    val attributes: String? = null
)
