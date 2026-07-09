package ru.asop.api.reference.dto.request

import jakarta.validation.constraints.Size
import java.util.UUID

data class ServiceUpdateRequest(
    @field:Size(max = 100)
    val serviceName: String? = null,

    @field:Size(max = 65535)
    val description: String? = null,

    val priority: Int? = null,

    val regionId: UUID? = null
)
