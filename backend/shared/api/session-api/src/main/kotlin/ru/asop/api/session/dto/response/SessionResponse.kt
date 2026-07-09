package ru.asop.api.session.dto.response

import java.time.Instant
import java.util.UUID

data class SessionResponse(
    val id: UUID,
    val sessionTypeId: UUID,
    val parentSessionId: UUID?,
    val terminalId: UUID?,
    val pathId: UUID?,
    val vehicleId: UUID?,
    val status: String,
    val startedAt: Instant,
    val closedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)
