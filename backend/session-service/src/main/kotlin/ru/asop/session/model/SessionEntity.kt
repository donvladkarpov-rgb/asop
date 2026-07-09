package ru.asop.session.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_SESSIONS")
data class SessionEntity(
    @Id
    val sessionId: UUID,
    val sessionTypeId: UUID,
    val parentSessionId: UUID?,
    val terminalId: UUID?,
    val pathId: UUID?,
    val vehicleId: UUID?,
    val status: String = "IN_PROGRESS",
    val startedAt: Instant,
    val closedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
