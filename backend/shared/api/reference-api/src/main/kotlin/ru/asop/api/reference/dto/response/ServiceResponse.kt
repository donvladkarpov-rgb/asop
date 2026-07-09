package ru.asop.api.reference.dto.response

import java.util.UUID

data class ServiceResponse(
    val id: UUID,
    val serviceName: String,
    val description: String?,
    val priority: Int,
    val regionId: UUID
)
