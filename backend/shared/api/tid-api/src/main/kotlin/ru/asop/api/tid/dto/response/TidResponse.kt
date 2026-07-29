package ru.asop.api.tid.dto.response

import java.time.Instant
import java.util.UUID

data class TidResponse(
    val id: UUID,
    val carrierId: UUID,
    val terminalId: UUID?,
    val tidValue: String,
    val status: String,
    val assignedAt: Instant?,
    val unassignedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)
