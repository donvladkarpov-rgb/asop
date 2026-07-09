package ru.asop.api.audit.dto.response

import java.time.Instant
import java.util.UUID

data class AuditTaskResponse(
    val id: UUID,
    val taskNumber: String,
    val status: String,
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
