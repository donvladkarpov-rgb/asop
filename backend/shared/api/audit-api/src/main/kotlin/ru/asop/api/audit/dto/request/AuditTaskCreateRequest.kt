package ru.asop.api.audit.dto.request

import jakarta.validation.constraints.NotBlank
import java.util.UUID

data class AuditTaskCreateRequest(
    @field:NotBlank
    val taskNumber: String,

    val organizerId: UUID? = null,

    val carrierId: UUID? = null,

    val description: String? = null
)
