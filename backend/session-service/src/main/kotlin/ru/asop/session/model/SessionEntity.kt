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
    val parentSessionId: UUID? = null,
    val terminalId: UUID? = null,
    val pathId: UUID? = null,
    val vehicleId: UUID? = null,
    val status: String = "IN_PROGRESS",
    val startedAt: Instant,
    val closedAt: Instant? = null,
    val startedAtLocal: Instant = startedAt,
    val closedAtLocal: Instant? = null,
    val expirationTime: Instant = startedAt.plus(java.time.Duration.ofHours(8))
)
