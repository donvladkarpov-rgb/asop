package ru.asop.carrier.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("ASOP_TIDS")
data class TidEntity(
    @Id
    val tidId: UUID,
    val carrierId: UUID,
    val terminalId: UUID? = null,
    val tidValue: String,
    val status: String = "UNUSED",
    val assignedAt: Instant? = null,
    val unassignedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)
